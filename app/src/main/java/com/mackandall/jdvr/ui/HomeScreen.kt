package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Home's panels: see-through over the weather sky, solid otherwise. */
private val LocalSeeThrough = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun homePanel() = if (LocalSeeThrough.current) Jc.Panel.copy(alpha = 0.62f) else Jc.Panel

@Composable
private fun homePanel2() = if (LocalSeeThrough.current) Jc.Panel2.copy(alpha = 0.72f) else Jc.Panel2

private fun localOf(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

/**
 * Home: back to the last channel (live, from the start, or the guide), what's recording now, new
 * recordings, and tonight's schedule. Refreshed every minute while it's open.
 */
@Composable
fun HomeScreen(
    entry: FocusRequester,
    onPlayChannel: (UUID) -> Unit,
    onPlayRecording: (PlayRecording) -> Unit,
    onOpenScreen: (com.mackandall.jdvr.Screen) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenLibraryItem: (UUID, LiveTvRepository.LibItem) -> Unit = { _, _ -> },
    // The library poster opened from here: Back puts focus on it again, not on Watch live.
    returnPoster: UUID? = null,
    onReturned: () -> Unit = {},
) {
    var last by remember { mutableStateOf<Channel?>(null) }
    var fromStart by remember { mutableStateOf<UUID?>(null) }
    var upcoming by remember { mutableStateOf<List<LiveTvRepository.Upcoming>>(emptyList()) }
    var fresh by remember { mutableStateOf<List<LiveTvRepository.RecordingCard>?>(null) }
    // Without Episode History, the new-recordings row is Jellyfin's "recently added" instead.
    var freshFromLibrary by remember { mutableStateOf(false) }
    // The first round has answered: until then, empty means "not yet", not "none".
    var loaded by remember { mutableStateOf(false) }
    var hasLiveTv by remember { mutableStateOf(true) }
    var tuners by remember { mutableStateOf<LiveTvRepository.TunerStatus?>(null) }
    var bufferIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    // Today's weather (Settings › Weather on Home), refreshed every 15 minutes.
    val prefs = Jdvr.prefs
    var weather by remember { mutableStateOf<com.mackandall.jdvr.data.WeatherNow?>(null) }
    LaunchedEffect(prefs.weatherOn, prefs.weatherPlace, prefs.fahrenheit) {
        weather = null
        val place = prefs.weatherPlace ?: return@LaunchedEffect
        if (!prefs.weatherOn) return@LaunchedEffect
        while (true) {
            runCatching { com.mackandall.jdvr.data.Weather.now(place, prefs.fahrenheit) }.getOrNull()?.let { weather = it }
            delay(15 * 60_000L)
        }
    }
    val scope = rememberCoroutineScope()
    val symbols = rememberMarkSymbols()
    var extras by remember { mutableStateOf<LiveTvRepository.HomeExtras?>(null) }

    // The clock.
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(5_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val channels = runCatching { Jdvr.liveTv.channels() }.getOrNull()
            // A failed call isn't "no Live TV": keep what was shown.
            if (channels != null) {
                hasLiveTv = channels.isNotEmpty()
                last = channels.firstOrNull { it.id.toString() == Jdvr.session.lastChannelId } ?: channels.firstOrNull()
            }
            fromStart = last?.now?.takeIf { it.isRecording }?.let { Jdvr.liveTv.recordingOfProgram(it.id) }
            upcoming = Jdvr.liveTv.schedule().orEmpty()
            bufferIds = Jdvr.liveTv.bufferPrograms()
            val shows = Jdvr.liveTv.recordedShows()
            freshFromLibrary = shows == null
            fresh = shows?.filter { it.unplayedCount > 0 }?.take(6) ?: Jdvr.liveTv.recentlyAdded(6).orEmpty()
            tuners = Jdvr.liveTv.tuners(null)
            extras = runCatching { Jdvr.liveTv.homeExtras() }.getOrNull()
            loaded = true
            delay(60_000)
        }
    }

    // What each tile has to show.
    val recordingNow = upcoming.filter { it.recordingNow }.sortedBy { it.programId?.plainId() in bufferIds }
    val nowLines =
        recordingNow.map { r ->
            val buffer = r.programId?.plainId() in bufferIds
            TileLine(
                key = r.timerId,
                title = r.name + (r.episodeTitle?.let { " · $it" } ?: ""),
                detail = listOfNotNull(r.channelNumber, r.channelName, if (buffer) "live TV buffer" else null).joinToString(" "),
                side = if (buffer) "buffer" else localOf(r.end)?.let { "until ${it.time()}" },
                dot = if (buffer) Jc.Recording.copy(alpha = 0.4f) else Jc.Recording,
            ) {
                // Watch it from the start; Back to live in the player tunes its channel.
                scope.launch {
                    val pid = r.programId?.toUuid() ?: return@launch
                    val channel = runCatching { Jdvr.liveTv.program(pid)?.channelId }.getOrNull()
                    Jdvr.liveTv.recordingOfProgram(pid)?.let { onPlayRecording(PlayRecording(it, startMs = 0L, liveChannelId = channel)) }
                }
            }
        }
    val nextLines =
        upcoming.filter { !it.recordingNow && it.programId?.plainId() !in bufferIds }.take(5).map { u ->
            val start = localOf(u.start)
            TileLine(
                key = u.timerId,
                title = u.name + (u.episodeTitle?.let { " · $it" } ?: ""),
                detail = listOfNotNull(listOfNotNull(u.channelNumber, u.channelName).joinToString(" ").ifEmpty { null }, if (u.kind == "series") "series" else if (u.kind == "favourite") "favourite" else "one-off").joinToString(" · "),
                side = start?.let { if (it.toLocalDate() == now.toLocalDate()) it.time() else it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.time() },
                dot = null,
            ) { onOpenScreen(com.mackandall.jdvr.Screen.Scheduled) }
        }
    val attentionLines =
        extras?.attention.orEmpty().mapIndexed { i, a ->
            TileLine(
                key = "a$i",
                title = a.title,
                detail = a.detail,
                side = null,
                dot = if (a.kind == "failed") Jc.Recording else Jc.Amber,
            ) {
                // The recording itself when there is one; else the schedule (where its re-record sits).
                a.itemId?.toUuid()?.let { onPlayRecording(PlayRecording(it)) } ?: onOpenScreen(com.mackandall.jdvr.Screen.Scheduled)
            }
        }

    // Which tiles show (Settings › Home): ON, OFF, or AUTO (only with something to show).
    fun shown(key: String, has: Boolean = true) =
        when (prefs.homeTile(key)) {
            com.mackandall.jdvr.data.Prefs.ON -> true
            com.mackandall.jdvr.data.Prefs.AUTO -> has
            else -> false
        }
    val showLive = shown("live")
    val showWeather = shown("weather") && prefs.weatherOn
    val showNow = shown("now", nowLines.isNotEmpty())
    val showNext = shown("next")
    // Without Episode History there is nothing to say here.
    val showAttention = extras != null && shown("attention", attentionLines.isNotEmpty())
    val showTuners = shown("tuners")
    val showFresh = shown("fresh")
    val showLibraries = shown("libraries")
    val row1 = showLive || showWeather || showNow
    val row2 = showNext || showAttention || showTuners
    val tileRows = (if (row1) 1 else 0) + (if (row2) 1 else 0)

    // Where focus starts: the first tile with something to press.
    val entryAt =
        when {
            showLive -> "live"
            showNow && nowLines.isNotEmpty() -> "now"
            showNext && nextLines.isNotEmpty() -> "next"
            showAttention && attentionLines.isNotEmpty() -> "attention"
            showFresh && !fresh.isNullOrEmpty() -> "fresh"
            else -> null
        }
    fun entryFor(key: String) = if (entryAt == key && returnPoster == null) Modifier.focusRequester(entry) else Modifier
    // Watch live holds the screen's entry, unless a library poster is getting focus back.
    val spare = remember { FocusRequester() }
    val liveEntry = if (returnPoster == null) entry else spare

    val w = weather
    androidx.compose.runtime.CompositionLocalProvider(LocalSeeThrough provides (w != null)) {
    Box(Modifier.fillMaxSize()) {
    if (w != null) {
        WeatherPicture(w.sky, w.isDay)
        // A light shade so text stays readable on bright skies; a light theme's dark text needs more
        // (the picture's dark ground, trees, night sky).
        Box(Modifier.fillMaxSize().background(Jc.Ink.copy(alpha = if (Jc.Light) 0.66f else 0.35f)))
    }
    // Home is one screen of tiles; the libraries' rows wait below it (▼ from New recordings), and
    // focus back in the tiles brings the whole page back up.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageHeight = maxHeight
    val page = rememberScrollState()
    // The page stays at the top unless focus is in the library rows: a focused tile's
    // bring-into-view, or focus going off to the menu, would otherwise nudge it up and cut off the clock.
    var inRows by remember { mutableStateOf(false) }
    LaunchedEffect(inRows) {
        if (!inRows) androidx.compose.runtime.snapshotFlow { page.value }.collect { if (it > 0) page.animateScrollTo(0) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(page)) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(pageHeight)
            .padding(start = 28.dp, top = 16.dp, end = 28.dp, bottom = 20.dp),
    ) {
        // The clock and date; the weather and tuners have tiles of their own.
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("JDVR", color = Jc.Focus, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Home", color = Jc.Muted, fontSize = 18.sp)
            Box(Modifier.weight(1f))
            Text(now.day(), color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp))
            Text(now.time(), color = Jc.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }

        // The tiles: each row shares its width by the tiles' weights, the rows share the height;
        // a tile turned off (or an Auto one with nothing to show) shrinks away and the others grow.
        val r1 = animatedWeight(if (row1) 1.2f else 0f)
        if (r1 > 0.01f) {
            Row(Modifier.weight(r1).fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val wl = animatedWeight(if (showLive) 3f else 0f)
                if (wl > 0.01f) {
                    Box(Modifier.weight(wl).fillMaxHeight()) {
                        if (loaded && !hasLiveTv) {
                            NoLiveTvCard(liveEntry, Modifier.fillMaxSize(), onSearch = { onOpenScreen(com.mackandall.jdvr.Screen.Search) })
                        } else {
                            LiveCard(last, fromStart, symbols, liveEntry, Modifier.fillMaxSize(), onPlayChannel, onPlayRecording, onGuide = { onOpenScreen(com.mackandall.jdvr.Screen.Guide) })
                        }
                    }
                }
                val ww = animatedWeight(if (showWeather) 2f else 0f)
                if (ww > 0.01f) {
                    WeatherTile(w, prefs.weatherPlace?.name?.substringBefore(','), Modifier.weight(ww).fillMaxHeight(), homePanel(), homePanel2())
                }
                val wn = animatedWeight(if (showNow) 2f else 0f)
                if (wn > 0.01f) {
                    ListTile("RECORDING NOW", nowLines, "Nothing is recording.", Modifier.weight(wn).fillMaxHeight(), homePanel(), homePanel2(), entryFor("now"), leftmost = !showLive && !showWeather)
                }
            }
        }
        val r2 = animatedWeight(if (row2) 1f else 0f)
        if (r2 > 0.01f) {
            Row(Modifier.weight(r2).fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val wx = animatedWeight(if (showNext) 3f else 0f)
                if (wx > 0.01f) {
                    ListTile("RECORDING NEXT", nextLines, "Nothing more scheduled.", Modifier.weight(wx).fillMaxHeight(), homePanel(), homePanel2(), entryFor("next"), leftmost = true)
                }
                val wa = animatedWeight(if (showAttention) 2f else 0f)
                if (wa > 0.01f) {
                    ListTile("NEEDS ATTENTION", attentionLines, "All recordings are fine.", Modifier.weight(wa).fillMaxHeight(), homePanel(), homePanel2(), entryFor("attention"), leftmost = !showNext)
                }
                val wt = animatedWeight(if (showTuners) 2f else 0f)
                if (wt > 0.01f) {
                    TunersTile(tuners, extras?.drive, Modifier.weight(wt).fillMaxHeight(), homePanel(), homePanel2())
                }
            }
        }

        // New recordings: a strip under the tiles, more room when there are fewer of them.
        val rf = animatedWeight(if (showFresh) (if (tileRows == 0) 3f else if (tileRows == 1) 1.1f else 0.78f) else 0f)
        if (rf > 0.01f) {
            Column(Modifier.weight(rf).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (freshFromLibrary) "Recently added" else "New recordings", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (freshFromLibrary) "unwatched · newest first · from your libraries" else "unwatched · newest first", color = Jc.Muted, fontSize = 13.sp)
                }
                val list = fresh
                when {
                    list == null || !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
                    list.isEmpty() -> Text("All caught up.", color = Jc.Muted, fontSize = 14.sp)
                    else ->
                        // As tall as the strip allows: the pictures shrink (keeping their shape) so
                        // the names under them always show.
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            list.forEachIndexed { i, c ->
                                NewCard(c, Modifier.weight(1f), if (i == 0) entryFor("fresh") else Modifier) {
                                    if (c.type == "Series") onOpenShow(c.id) else c.id.toUuid()?.let { onPlayRecording(PlayRecording(it)) }
                                }
                            }
                            repeat(6 - list.size) { Box(Modifier.weight(1f)) }
                        }
                }
            }
        }
        if (!row1 && !row2 && !showFresh) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Turn Home's tiles on in Settings › Home.", color = Jc.Muted, fontSize = 18.sp)
            }
        }
    }
    if (showLibraries) {
        LibraryRows(onOpenLibraryItem, returnPoster, entry, onReturned, Modifier.onFocusChanged { inRows = it.hasFocus })
    } else if (returnPoster != null) {
        LaunchedEffect(Unit) { onReturned() }
    }
    }
    }
    }
    }
}

private var libraryRowsCache: List<Pair<LiveTvRepository.Library, List<LiveTvRepository.LibItem>>> = emptyList()

/**
 * "Recently added in …", one row per library (menu order, hidden ones left out), as on the web
 * client's home page; refreshed every five minutes.
 */
@Composable
private fun LibraryRows(
    onOpen: (UUID, LiveTvRepository.LibItem) -> Unit,
    returnPoster: UUID?,
    entry: FocusRequester,
    onReturned: () -> Unit,
    modifier: Modifier,
) {
    val prefs = Jdvr.prefs
    // Kept between visits, so the poster to return to is there at once.
    var rows by remember { mutableStateOf(libraryRowsCache) }
    LaunchedEffect(prefs.hiddenLibraries, prefs.homeHiddenLibraries, prefs.libraryOrder) {
        while (true) {
            runCatching { Jdvr.liveTv.libraries() }.getOrNull()?.let { libs ->
                rows =
                    libs.filter { it.id.toString() !in prefs.hiddenLibraries && it.id.toString() !in prefs.homeHiddenLibraries }.inMenuOrder().mapNotNull { l ->
                        Jdvr.liveTv.latestIn(l.id)?.takeIf { it.isNotEmpty() }?.let { l to it }
                    }
                libraryRowsCache = rows
                // The poster is gone (deleted, or no longer among the latest): focus goes to Watch live.
                if (returnPoster != null && rows.none { (_, items) -> items.any { it.id == returnPoster } }) onReturned()
            }
            delay(5 * 60_000L)
        }
    }
    Column(modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { (lib, items) ->
            Text("Recently added in ${lib.name}", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            // Starts at the poster to return to, so it's composed and can take focus.
            val start = items.indexOfFirst { it.id == returnPoster }.coerceAtLeast(0)
            LazyRow(
                state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = start),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                    val back = item.id == returnPoster
                    val m =
                        Modifier
                            // ◄ from the first poster opens the menu.
                            .then(if (i == 0) Modifier.focusProperties { left = FocusRequester.Cancel } else Modifier)
                            .then(if (back) Modifier.focusRequester(entry).onFocusChanged { if (it.isFocused) onReturned() } else Modifier)
                    PosterCard(item, m) { onOpen(lib.id, item) }
                }
            }
        }
    }
}

/** A library poster: the show's for an episode; its name, and the episode or year under it. */
@Composable
private fun PosterCard(
    item: LiveTvRepository.LibItem,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = homePanel2(), focusedContainerColor = homePanel2()),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.liveTv.imageUrl(item.seriesId ?: item.id, 330), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val badge = item.unplayed?.takeIf { it > 0 && item.type == "Series" }?.toString()
                badge?.let {
                    Text(
                        it,
                        color = Jc.Text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Jc.Ink.copy(alpha = 0.85f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(item.seriesName ?: item.name, color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = if (item.seriesName != null) listOfNotNull(item.seasonEpisode, item.name).joinToString(" · ") else item.year?.toString()
        sub?.let { Text(it, color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun HomeChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(containerColor = homePanel(), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun LiveCard(
    channel: Channel?,
    fromStart: UUID?,
    symbols: LiveTvRepository.MarkSymbols?,
    entry: FocusRequester,
    modifier: Modifier,
    onPlayChannel: (UUID) -> Unit,
    onPlayRecording: (PlayRecording) -> Unit,
    onGuide: () -> Unit,
) {
    val p = channel?.now
    Column(
        modifier.background(homePanel(), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("BACK TO LIVE · LAST CHANNEL", color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(listOfNotNull(channel?.number, channel?.name).joinToString(" "), color = Jc.Muted, fontSize = 16.sp)
            Text(p?.name ?: channel?.name ?: "…", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sub = listOfNotNull(p?.let { listOfNotNull(it.season?.let { s -> "S$s" }, it.episode?.let { e -> "E$e" }).joinToString("").ifEmpty { null } }, p?.rawEpisodeTitle?.let { Marks.parse(it, symbols).title }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = Jc.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (p != null) {
            val total = Duration.between(p.start, p.end).toMinutes().coerceAtLeast(1)
            val done = Duration.between(p.start, LocalDateTime.now()).toMinutes().coerceIn(0, total)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.start.time(), color = Jc.Muted, fontSize = 13.sp)
                Box(Modifier.weight(1f).height(5.dp).background(Jc.Line, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total).background(Jc.Focus, RoundedCornerShape(3.dp)))
                }
                Text(p.end.time(), color = Jc.Muted, fontSize = 13.sp)
                if (p.isRecording) Text("● Recording", color = Jc.Recording, fontSize = 13.sp)
            }
        }
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // A TV not allowed live TV (JellyDVR Space's device rights) keeps the button for focus, and says so.
            val live = deviceRights().liveTv
            // ◄ from here opens the menu (focus search would jump down to the first poster).
            Button(if (live) "Watch live" else "Live TV is off for this TV", primary = live, modifier = Modifier.focusRequester(entry).focusProperties { left = FocusRequester.Cancel }) { if (live) channel?.let { onPlayChannel(it.id) } }
            if (fromStart != null) {
                Button("Watch from start") { onPlayRecording(PlayRecording(fromStart, startMs = 0L, liveChannelId = channel?.id)) }
            }
            Button("Guide", onClick = onGuide)
        }
    }
}

/** Home's first card on a server with no Live TV: says so, and offers Search (focus starts here). */
@Composable
private fun NoLiveTvCard(
    entry: FocusRequester,
    modifier: Modifier,
    onSearch: () -> Unit,
) {
    Column(
        modifier.background(homePanel(), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LIVE TV", color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(NO_LIVE_TV, color = Jc.Text, fontSize = 16.sp)
        Box(Modifier.weight(1f))
        Button("Search your libraries", primary = true, modifier = Modifier.focusRequester(entry), onClick = onSearch)
        // This card replaces the live card once Home knows there's no Live TV; the focus that was
        // on its Watch live button went with it, so it comes here.
        LaunchedEffect(Unit) {
            repeat(20) {
                if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
}

@Composable
private fun SmallRow(
    title: String,
    detail: String,
    dot: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = homePanel2(), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (dot) Box(Modifier.width(9.dp).height(9.dp).background(Jc.Recording, RoundedCornerShape(50)))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Jc.Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NewCard(
    c: LiveTvRepository.RecordingCard,
    modifier: Modifier,
    surfaceModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier.weight(1f, fill = false).aspectRatio(16f / 9f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = homePanel2(), focusedContainerColor = homePanel2()),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Jdvr.liveTv.imageUrl(c.imageId ?: c.id, 300)?.let { AsyncImage(model = it, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Text(
                    if (c.count > 1) "${c.unplayedCount} new" else "New",
                    color = Jc.Text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Jc.Ink.copy(alpha = 0.85f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(c.name, color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.subtitle?.let { Text(it, color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
