package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    val partial: Boolean = false,
    val partialMinutes: Int? = null,
) {
    val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else episode?.let { "E$it" }
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
) {
    val tiles = Jdvr.prefs.episodeView == Prefs.TILES
    var backdrop by remember { mutableStateOf<UUID?>(null) }
    var overview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesId) {
        seriesId?.let { id -> runCatching { Jdvr.liveTv.details(id) }.getOrNull()?.let { backdrop = it.backdropOf; overview = it.item.overview } }
    }

    Box(Modifier.fillMaxSize()) {
        backdrop?.let {
            AsyncImage(model = Jdvr.liveTv.backdropUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Jc.Ink.copy(alpha = 0.88f)))
        }
        val list = entries
        if (list == null || list.isEmpty()) {
            Column(Modifier.padding(start = 28.dp, top = 22.dp)) {
                Header(title, subtitle, null, tiles)
                Spacer(12)
                Text(if (list == null) "Loading…" else emptyText, color = Jc.Muted, fontSize = 16.sp)
            }
        } else if (tiles) {
            TilesView(seriesId, title, subtitle, overview, list, entry, onPlay, onInfo)
        } else {
            ListView(title, subtitle, list, entry, onPlay, onInfo, onToggleWatched)
        }
    }
}

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
            Button(if (tiles) "View: Tiles" else "View: List") { Jdvr.prefs.changeEpisodeView(if (tiles) Prefs.LIST else Prefs.TILES) }
        }
        overview?.let { Text(it, color = Jc.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f)) }
    }
}

// ---- List: rows on the left, the focused episode on the right ----------------------------------

@Composable
private fun ListView(
    title: String,
    subtitle: String?,
    list: List<EpisodeEntry>,
    entry: FocusRequester?,
    onPlay: (EpisodeEntry) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
    onToggleWatched: ((EpisodeEntry) -> Unit)?,
) {
    var focusedId by remember { mutableStateOf(list.first().id) }
    val focused = list.firstOrNull { it.id == focusedId } ?: list.first()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Descriptions the list didn't come with (recordings), fetched as episodes get focus.
    val more = remember { mutableStateMapOf<UUID, LiveTvRepository.LibItem>() }
    LaunchedEffect(focused.id) {
        if (focused.overview == null && focused.id !in more) runCatching { Jdvr.liveTv.libItem(focused.id) }.getOrNull()?.let { more[focused.id] = it }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp)) {
        Header(title, subtitle, null, tiles = false)
        Spacer(12)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                    // A season heading where the season changes (when there's more than one).
                    if (list.any { it.season != list.first().season } && (i == 0 || list[i - 1].season != e.season)) {
                        Text(seasonTitle(e.season), color = Jc.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = { onPlay(e) },
                            modifier =
                                (if (i == 0) Modifier.focusRequester(first).let { m -> entry?.let { m.focusRequester(it) } ?: m } else Modifier)
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onFocusChanged { if (it.isFocused) focusedId = e.id },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel.copy(alpha = 0.92f), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(e.seasonEpisode ?: e.recordedAt?.day() ?: "", color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.width(78.dp))
                                Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Badges(e)
                            }
                        }
                        Button("Info") { onInfo(e) }
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
    Column(modifier.background(Jc.Panel.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(
            model = Jdvr.liveTv.imageUrl(e.id, 300),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Jc.Panel2),
        )
        Text(e.name, color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                e.seasonEpisode,
                e.recordedAt?.let { "recorded ${it.day()} ${it.time()}" },
                (e.runtimeMin ?: more?.runtimeMin)?.let { "$it min" },
            ).joinToString(" · "),
            color = Jc.Muted,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Badges(e) }
        (e.overview ?: more?.overview)?.let { Text(it, color = Jc.Text, fontSize = 13.sp, maxLines = 7, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Badges(e: EpisodeEntry) {
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
    color = if (dark) Color(0xFF1A1206) else Jc.Text,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.background(color.copy(alpha = 0.92f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
)

// ---- Tiles: the web client's layout -------------------------------------------------------------

@Composable
private fun TilesView(
    seriesId: UUID?,
    title: String,
    subtitle: String?,
    overview: String?,
    list: List<EpisodeEntry>,
    entry: FocusRequester?,
    onPlay: (EpisodeEntry) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
) {
    val bySeason = list.groupBy { it.season }
    val seasons = bySeason.keys.toList()
    // Start on the first season with something unwatched.
    var season by remember { mutableStateOf(seasons.firstOrNull { s -> bySeason[s].orEmpty().any { !it.played } } ?: seasons.first()) }
    // Season posters, when the show is in the library.
    var seasonItems by remember { mutableStateOf<List<LiveTvRepository.LibItem>>(emptyList()) }
    LaunchedEffect(seriesId) { seriesId?.let { id -> seasonItems = runCatching { Jdvr.liveTv.seasons(id) }.getOrDefault(emptyList()) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(season) { runCatching { first.requestFocus() } }

    val shown = bySeason[season].orEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(title, subtitle, overview, tiles = true) }
        if (seasons.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Seasons", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(seasons, key = { it ?: -1 }) { s ->
                            val poster = seasonItems.firstOrNull { it.episode == s || (s == null && it.episode == null) }
                            SeasonFolder(seasonTitle(s), bySeason[s].orEmpty().size, poster?.id ?: seriesId, s == season) { season = s }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text(seasonTitle(season), color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${shown.size} episode" + (if (shown.size == 1) "" else "s") + " · OK plays · Menu for details", color = Jc.Muted, fontSize = 13.sp)
            }
        }
        itemsIndexed(shown, key = { _, e -> e.id }) { i, e ->
            EpisodeTile(e, if (i == 0) Modifier.focusRequester(first).let { m -> entry?.let { m.focusRequester(it) } ?: m } else Modifier, onPlay, onInfo)
        }
    }
}

@Composable
private fun SeasonFolder(
    name: String,
    count: Int,
    imageOf: UUID?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(border = if (selected) Border(BorderStroke(2.dp, Jc.Muted)) else Border.None, focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            imageOf?.let { AsyncImage(model = Jdvr.liveTv.imageUrl(it, 300), contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(name, color = if (selected) Jc.Text else Jc.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("$count", color = Jc.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun EpisodeTile(
    e: EpisodeEntry,
    modifier: Modifier,
    onPlay: (EpisodeEntry) -> Unit,
    onInfo: (EpisodeEntry) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = { onPlay(e) },
            onLongClick = { onInfo(e) },
            modifier =
                modifier.fillMaxWidth().aspectRatio(16f / 9f).onPreviewKeyMenu { onInfo(e) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.liveTv.imageUrl(e.id, 300), contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Badges(e) }
                if (e.resumeMs > 0 && !e.played && e.runtimeMin != null) {
                    val f = (e.resumeMs / 60_000f / e.runtimeMin).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Jc.Line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Jc.Focus))
                    }
                }
            }
        }
        Text(listOfNotNull(e.seasonEpisode, e.name).joinToString(" · "), color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        e.recordedAt?.let { Text("Recorded ${it.day()} ${it.time()}", color = Jc.Muted, fontSize = 11.sp) }
    }
}

/** The remote's Menu key on a tile opens its details (OK plays). */
private fun Modifier.onPreviewKeyMenu(action: () -> Unit) =
    this.then(
        Modifier.onPreviewKeyEvent { k ->
            if (k.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && k.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_MENU) {
                action()
                true
            } else {
                false
            }
        },
    )
