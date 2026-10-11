package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.launch

/** One episode as both episode lists show it (a recording, or a library episode). */
data class EpisodeEntry(
    val id: UUID,
    val name: String,
    val season: Int?,
    val episode: Int?,
    val played: Boolean,
    val resumeMs: Long = 0L,
    val runtimeMin: Long? = null,
    val overview: String? = null,
    val recordedAt: LocalDateTime? = null,
    val archived: Boolean = false,
    // Whether the file is an archive copy, whatever the list shows as a tag.
    val inArchive: Boolean = false,
    val partial: Boolean = false,
    val partialMinutes: Int? = null,
    // Comskip's badge ("4m ads", "scanning", "no ads"…), when it has scanned this recording.
    val commercials: String? = null,
) {
    val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else episode?.let { "E$it" }
}

/** The entries with Comskip's commercial badges added. */
suspend fun withCommercialBadges(list: List<EpisodeEntry>): List<EpisodeEntry> {
    val badges = runCatching { Jdvr.liveTv.commercialBadges(list.map { it.id }) }.getOrDefault(emptyMap())
    return if (badges.isEmpty()) list else list.map { e -> badges[e.id.toString().replace("-", "")]?.let { e.copy(commercials = it.label) } ?: e }
}

private fun seasonTitle(n: Int?) = when (n) {
    null -> "Other recordings"
    0 -> "Specials"
    else -> "Season $n"
}

/**
 * A show's episodes in either view (View button, remembered): a list with the focused episode's
 * details beside it, or the web client's tiles under a season heading, with the show's seasons as
 * folders across the top when there's more than one. OK plays; Info opens the details page;
 * [onToggleWatched] (library) adds a Watched button.
 */
@Composable
fun EpisodeBrowser(
    seriesId: UUID?,
    title: String,
    subtitle: String?,
    entries: List<EpisodeEntry>?,
    entry: FocusRequester?,
    onPlay: (EpisodeEntry) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
    onToggleWatched: ((EpisodeEntry) -> Unit)? = null,
    emptyText: String = "No episodes.",
    onPlayFromStart: (EpisodeEntry) -> Unit = onPlay,
    // More buttons for the focused episode in the tiles view (Recorded Shows: favourite, delete).
    actions: (@Composable androidx.compose.foundation.layout.RowScope.(EpisodeEntry, (String) -> Unit) -> Unit)? = null,
    // Hold OK (or Menu) on an episode: its quick actions, or (Settings › Remote) its details.
    onHold: ((EpisodeEntry) -> Unit)? = null,
    // After the show's own actions changed something (watched, deleted, moved): reload.
    onShowChanged: () -> Unit = {},
) {
    // The show's actions (mark all watched, move or delete all of it, forget it) behind a button
    // of their own, so they don't depend on holding OK.
    var showMenu by remember { mutableStateOf(false) }
    if (showMenu && seriesId != null) {
        QuickActions(
            itemId = seriesId,
            onPlay = {},
            onDetails = {},
            onChanged = onShowChanged,
            onDismiss = { showMenu = false },
            extra = { _, say, done -> ShowActions(seriesId, say, onDeleted = done) },
            onShowPage = true,
        )
    }
    val openShowMenu: (() -> Unit)? = if (seriesId != null) { { showMenu = true } } else null
    val hold: ((EpisodeEntry) -> Unit)? = if (!holdEnabled) null else { e -> holdOk(menu = { onHold?.invoke(e) ?: onInfo(e) }, details = { onInfo(e) }) }
    val tiles = Jdvr.prefs.episodeView == Prefs.TILES
    var backdrop by remember { mutableStateOf<UUID?>(null) }
    var overview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId) {
        seriesId?.let { id -> runCatching { Jdvr.liveTv.details(id) }.getOrNull()?.let { backdrop = it.backdropOf; overview = it.item.overview } }
    }

    Box(Modifier.fillMaxSize()) {
        val list = entries
        if (!tiles || list.isNullOrEmpty()) {
            backdrop?.let {
                AsyncImage(model = Jdvr.liveTv.backdropUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Jc.Ink.copy(alpha = 0.88f)))
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalShowActions provides openShowMenu) {
        if (list == null || list.isEmpty()) {
            Column(Modifier.padding(start = 28.dp, top = 22.dp)) {
                Header(title, subtitle, null, tiles)
                Spacer(12)
                Text(if (list == null) "Loading…" else emptyText, color = Jc.Muted, fontSize = 16.sp)
            }
        } else if (tiles) {
            TilesView(seriesId, backdrop, title, subtitle, list, entry, { e, fromStart -> if (fromStart) onPlayFromStart(e) else onPlay(e) }, onInfo, onToggleWatched, actions, hold)
        } else {
            ListView(seriesId?.toString() ?: title, title, subtitle, list, entry, onPlay, onInfo, onToggleWatched, hold)
        }
        }
    }
}

/** Opens the show's actions menu from a show page's header; null where there's no show to act on. */
private val LocalShowActions = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

@Composable
private fun Header(
    title: String,
    subtitle: String?,
    overview: String?,
    tiles: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            subtitle?.let { Text(it, color = Jc.Muted, fontSize = 14.sp, maxLines = 1) }
            Box(Modifier.weight(0.01f))
            LocalShowActions.current?.let { Button("Show actions", onClick = it) }
            Button(if (tiles) "View: Tiles" else "View: List") { Jdvr.prefs.changeEpisodeView(if (tiles) Prefs.LIST else Prefs.TILES) }
        }
        overview?.let { Text(it, color = Jc.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f)) }
    }
}

// ---- List: rows on the left, the focused episode on the right ----------------------------------

/**
 * The episode last pressed in each show's list (by series id or title): a details page replaces the
 * list, so on Back the list is new and would start at the top. Kept while the app runs.
 */
private object ListReturn {
    val last = mutableMapOf<String, UUID>()
}

@Composable
private fun ListView(
    showKey: String,
    title: String,
    subtitle: String?,
    list: List<EpisodeEntry>,
    entry: FocusRequester?,
    onPlay: (EpisodeEntry) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
    onToggleWatched: ((EpisodeEntry) -> Unit)?,
    onHold: ((EpisodeEntry) -> Unit)?,
) {
    // Back from an episode's page (a new list): the episode that was pressed, else the first.
    val remembered = ListReturn.last[showKey]?.takeIf { id -> list.any { it.id == id } }
    var focusedId by remember { mutableStateOf(remembered ?: list.first().id) }
    val focused = list.firstOrNull { it.id == focusedId } ?: list.first()
    val first = remember { FocusRequester() }
    // The episode just played (or opened): back from the player or its page, the list reloads and
    // focus goes to that row again, scrolled into view. (Focus asked for the first row before,
    // which in a long list isn't on screen, so Android dropped it on a row nearby.)
    var returnTo by remember { mutableStateOf(remembered) }
    val back = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Focus coming back into the list (the player or a page over it closed): Android puts it on
    // whatever row is under the spot the closed screen's button was, so move it to the row that
    // was pressed.
    var listHasFocus by remember { mutableStateOf(false) }
    fun backToPressed() {
        val id = returnTo ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) runCatching { listState.scrollToItem(index) }
            repeat(40) {
                if (runCatching { back.requestFocus() }.isSuccess) return@launch
                kotlinx.coroutines.delay(50)
            }
        }
    }
    // First showing: the remembered episode (scrolled to), or the first.
    LaunchedEffect(Unit) {
        if (returnTo == null) {
            runCatching { first.requestFocus() }
        } else {
            backToPressed()
        }
    }
    LaunchedEffect(list) {
        val id = returnTo ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) {
            returnTo = null
            return@LaunchedEffect
        }
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) runCatching { listState.scrollToItem(index) }
        repeat(40) {
            if (runCatching { back.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
    // Descriptions the list didn't come with (recordings), fetched as episodes get focus.
    val more = remember { mutableStateMapOf<UUID, LiveTvRepository.LibItem>() }
    LaunchedEffect(focused.id) {
        if (focused.overview == null && focused.id !in more) runCatching { Jdvr.liveTv.libItem(focused.id) }.getOrNull()?.let { more[focused.id] = it }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp)) {
        Header(title, subtitle, null, tiles = false)
        Spacer(12)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(
                Modifier
                    .weight(0.6f)
                    .onFocusChanged { f ->
                        if (f.hasFocus && !listHasFocus) backToPressed()
                        listHasFocus = f.hasFocus
                    }
                    // Moving with the arrows ends the "return here" (the row pressed stays the
                    // return point while the player or its page is up, whatever Android does with
                    // focus meanwhile).
                    .onPreviewKeyEvent { k ->
                        if (k.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            k.key in setOf(androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.DirectionDown)
                        ) {
                            returnTo = null
                        }
                        false
                    },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                    // A season heading where the season changes (when there's more than one).
                    if (list.any { it.season != list.first().season } && (i == 0 || list[i - 1].season != e.season)) {
                        Text(seasonTitle(e.season), color = Jc.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp))
                    }
                    // Focus anywhere in the row (the episode, or its Info / Watched buttons) shows
                    // that episode on the right.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).onFocusChanged { if (it.hasFocus) focusedId = e.id },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = {
                                returnTo = e.id
                                ListReturn.last[showKey] = e.id
                                onPlay(e)
                            },
                            onLongClick = onHold?.let { h -> { h(e) } },
                            modifier =
                                (if (i == 0) Modifier.focusRequester(first) else Modifier)
                                    // The screen's entry (asked for after the player closes) and this
                                    // list's own return point: the row last focused.
                                    .then(if (e.id == (returnTo ?: focusedId)) Modifier.focusRequester(back).let { m -> entry?.let { m.focusRequester(it) } ?: m } else Modifier)
                                    .weight(1f)
                                    .heightIn(min = 64.dp)
                                    .then(onHold?.let { h -> Modifier.onMenuKey { h(e) } } ?: Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel.seeThrough(0.92f), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(e.seasonEpisode ?: e.recordedAt?.day() ?: "", color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.width(78.dp))
                                // Badges under the title, so they never squeeze it away; more than fit go on
                                // another line and the row grows.
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(e.shortName(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    BadgeLine(e)
                                }
                            }
                        }
                        Button("Info") {
                            returnTo = e.id
                            ListReturn.last[showKey] = e.id
                            onInfo(e)
                        }
                        onToggleWatched?.let { t -> Button(if (e.played) "Unwatch" else "Watched") { t(e) } }
                    }
                }
            }
            EpisodePane(focused, more[focused.id], Modifier.weight(0.4f))
        }
    }
}

@Composable
private fun EpisodePane(
    e: EpisodeEntry,
    more: LiveTvRepository.LibItem?,
    modifier: Modifier,
) {
    Column(modifier.background(Jc.Panel.seeThrough(0.9f), RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(
            model = Jdvr.liveTv.imageUrl(e.id, 300),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Jc.Panel2),
        )
        Text(e.shortName(), color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                e.seasonEpisode,
                e.recordedAt?.let { "recorded ${it.day()} ${it.time()}" },
                (e.runtimeMin ?: more?.runtimeMin)?.let { "$it min" },
            ).joinToString(" · "),
            color = Jc.Muted,
            fontSize = 13.sp,
        )
        BadgeLine(e)
        (e.overview ?: more?.overview)?.let { Text(it, color = Jc.Text, fontSize = 13.sp, maxLines = 7, overflow = TextOverflow.Ellipsis) }
    }
}

/** The name without a leading "S3:E1 - " when the row already shows the season and episode. */
private fun EpisodeEntry.shortName(): String {
    val se = seasonEpisode ?: return name
    return name.removePrefix("$se - ").ifBlank { name }
}

/** An episode's badges, wrapping onto more lines when they don't fit (so any mix of them lays out). */
@Composable
private fun BadgeLine(e: EpisodeEntry) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Badges(e) }

@Composable
private fun Badges(e: EpisodeEntry) {
    movingFor(e.id.toString())?.let { MovingTag(it) }
    cuttingFor(e.id.toString())?.let { CuttingTag(it) }
    e.commercials?.let { Tag("✂ $it", if (it.startsWith("scan failed")) Jc.Recording else Jc.Panel2) }
    if (e.archived) Tag("Archive", Jc.Panel2)
    if (e.partial) Tag("Partial" + (e.partialMinutes?.let { " · $it min" } ?: ""), Jc.Amber, dark = true)
    if (e.resumeMs > 0 && !e.played) Tag("${e.resumeMs / 60_000} min in", Jc.FocusFill)
    if (e.played) Chip("watched", Jc.Green)
}

@Composable
private fun Tag(
    text: String,
    color: Color,
    dark: Boolean = false,
) = Text(
    text,
    color = badgeText(color, dark),
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    softWrap = false,
    modifier = Modifier.background(color.copy(alpha = 0.92f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
)

// ---- Tiles: Wholphin's show page ---------------------------------------------------------------

/**
 * Over the show's backdrop: season tabs, the title, everything about the focused episode (S/E,
 * details, video / audio, description), one row of episode cards (the others dimmed), its buttons,
 * then its cast and guest stars below.
 */
@Composable
private fun TilesView(
    seriesId: UUID?,
    backdrop: UUID?,
    title: String,
    subtitle: String?,
    list: List<EpisodeEntry>,
    entry: FocusRequester?,
    onPlay: (EpisodeEntry, Boolean) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
    onToggleWatched: ((EpisodeEntry) -> Unit)?,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.(EpisodeEntry, (String) -> Unit) -> Unit)?,
    onHold: ((EpisodeEntry) -> Unit)?,
) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) {
        if (note != null) {
            kotlinx.coroutines.delay(4000)
            note = null
        }
    }
    val bySeason = list.groupBy { it.season }
    val seasons = bySeason.keys.toList()
    var season by remember { mutableStateOf(seasons.firstOrNull { s -> bySeason[s].orEmpty().any { !it.played } } ?: seasons.first()) }
    val shown = bySeason[season].orEmpty()
    var focusedId by remember(season) { mutableStateOf((shown.firstOrNull { !it.played } ?: shown.first()).id) }
    val focused = shown.firstOrNull { it.id == focusedId } ?: shown.first()
    var rowHasFocus by remember { mutableStateOf(false) }
    // Full details (video, audio, credits, description) for episodes as they get focus.
    val details = remember { mutableStateMapOf<UUID, LiveTvRepository.ItemDetails>() }
    LaunchedEffect(focusedId) {
        if (focusedId !in details) {
            kotlinx.coroutines.delay(200)
            runCatching { Jdvr.liveTv.details(focusedId) }.getOrNull()?.let { details[focusedId] = it }
        }
    }
    val d = details[focused.id]
    val start = remember(season) { FocusRequester() }
    LaunchedEffect(season) { runCatching { start.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        backdrop?.let {
            AsyncImage(model = Jdvr.liveTv.backdropUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Jc.Ink.copy(alpha = 0.97f), 0.6f to Jc.Ink.copy(alpha = 0.8f), 1f to Jc.Ink.copy(alpha = 0.35f))))
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 14.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Season tabs and the view switch.
            Row(Modifier.padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (seasons.size > 1) {
                    seasons.forEach { s -> SeasonTab(seasonTitle(s), s == season) { season = s } }
                } else {
                    Text(seasonTitle(season), color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Box(Modifier.weight(1f))
                subtitle?.let { Text(it, color = Jc.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp)) }
                LocalShowActions.current?.let { Button("Show actions", onClick = it) }
                Button("View: Tiles") { Jdvr.prefs.changeEpisodeView(Prefs.LIST) }
            }
            Text(title, color = Jc.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 36.dp))

            // The focused episode.
            Column(Modifier.padding(horizontal = 36.dp).fillMaxWidth(0.62f).height(128.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    focused.seasonEpisode?.let { Text(it, color = Jc.Muted, fontSize = 16.sp) }
                    Text(focused.shortName(), color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d?.premiere?.let { Text(it.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")), color = Jc.Text, fontSize = 13.sp) }
                    (focused.runtimeMin ?: d?.item?.runtimeMin)?.let { Text("$it min", color = Jc.Text, fontSize = 13.sp) }
                    d?.item?.officialRating?.let { Text(it, color = Jc.Text, fontSize = 12.sp, modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp)) }
                    d?.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = 13.sp) }
                    focused.recordedAt?.let { Text("Recorded ${it.day()} ${it.time()}", color = Jc.Muted, fontSize = 13.sp) }
                }
                BadgeLine(focused)
                d?.let { listOfNotNull(it.video, it.audio.firstOrNull(), it.subtitles.firstOrNull()).joinToString(" · ").ifEmpty { null } }?.let {
                    Text(it, color = Jc.Muted, fontSize = 12.sp, maxLines = 1)
                }
                (focused.overview ?: d?.item?.overview)?.let { Text(it, color = Jc.Text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }

            // One row of episodes; the ones not focused are dimmed while the row has focus.
            LazyRow(
                Modifier.onFocusChanged { rowHasFocus = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 36.dp),
            ) {
                itemsIndexed(shown, key = { _, e -> e.id }) { i, e ->
                    val isStart = e.id == (shown.firstOrNull { !it.played } ?: shown.first()).id
                    EpisodeCard(
                        e = e,
                        dim = rowHasFocus && e.id != focused.id,
                        modifier = (if (isStart) Modifier.focusRequester(start).let { m -> entry?.let { m.focusRequester(it) } ?: m } else Modifier).onFocusChanged { if (it.isFocused) focusedId = e.id },
                        onClick = { onPlay(e, false) },
                        onLongClick = onHold?.let { h -> { h(e) } },
                    )
                }
            }

            // The focused episode's buttons (wrapping when there are many).
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Recordings' lists carry no resume point: the details have it.
                val resumeMs = focused.resumeMs.takeIf { it > 0 } ?: d?.item?.resumeMs ?: 0L
                val resume = resumeMs > 0 && !focused.played
                Button(if (resume) "Resume from ${resumeMs / 60_000} min" else "Play", primary = true) { onPlay(focused, false) }
                if (resume) Button("Play from start") { onPlay(focused, true) }
                onToggleWatched?.let { t -> Button(if (focused.played) "Mark unwatched" else "Mark watched") { t(focused) } }
                Button("Details") { onInfo(focused) }
                actions?.invoke(this, focused) { note = it }
            }
            note?.let { Text(it, color = Jc.Focus, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 36.dp)) }

            // Who's in it.
            d?.let { det ->
                val (guests, crew) = det.people.partition { it.kind == "GuestStar" }
                if (crew.isNotEmpty()) PeopleRow("Cast & crew", crew)
                if (guests.isNotEmpty()) PeopleRow("Guest stars in this episode", guests)
            }
        }
    }
}

@Composable
private fun SeasonTab(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Jc.FocusFill, contentColor = if (selected) Jc.Text else Jc.Muted, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Box(Modifier.width(36.dp).height(3.dp).background(if (selected) Jc.Focus else Color.Transparent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun EpisodeCard(
    e: EpisodeEntry,
    dim: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(Modifier.width(176.dp).alpha(if (dim) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).then(onLongClick?.let { Modifier.onMenuKey(it) } ?: Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.liveTv.imageUrl(e.id, 300), contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                movingFor(e.id.toString())?.let { MovingOverlay(it) } ?: cuttingFor(e.id.toString())?.let { CuttingOverlay(it) }
                e.episode?.let {
                    Text("E$it", color = Jc.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Jc.Ink.copy(alpha = 0.85f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                if (e.played) {
                    Text("✓", color = Jc.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Jc.Green, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                if (e.partial) {
                    Text("Partial", color = Jc.OnAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Jc.Amber, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
                if (e.resumeMs > 0 && !e.played && e.runtimeMin != null) {
                    val f = (e.resumeMs / 60_000f / e.runtimeMin).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Jc.Line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Jc.Focus))
                    }
                }
            }
        }
        Text(e.name, color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PeopleRow(
    title: String,
    people: List<LiveTvRepository.Person>,
) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 36.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 30.dp)) {
            items(people.take(30), key = { it.id.toString() + it.role + it.kind }) { p ->
                Surface(
                    onClick = {},
                    modifier = Modifier.width(96.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.width(70.dp).height(70.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Jc.Panel2), contentAlignment = Alignment.Center) {
                            if (p.hasImage) {
                                AsyncImage(model = Jdvr.liveTv.personImageUrl(p.id), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(p.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), color = Jc.Muted, fontSize = 18.sp)
                            }
                        }
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p.role ?: p.kind, color = Jc.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The remote's Menu key on a card opens its details (OK plays). */
