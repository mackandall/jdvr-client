package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusRequester
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

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
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
            fresh = shows?.filter { it.unplayedCount > 0 }?.take(5) ?: Jdvr.liveTv.recentlyAdded(5).orEmpty()
            tuners = Jdvr.liveTv.tuners(null)
            loaded = true
            delay(60_000)
        }
    }

    val w = weather
    androidx.compose.runtime.CompositionLocalProvider(LocalSeeThrough provides (w != null)) {
    Box(Modifier.fillMaxSize()) {
    if (w != null) {
        WeatherPicture(w.sky, w.isDay)
        // A light shade so text stays readable on bright skies; a light theme's dark text needs more
        // (the picture's dark ground, trees, night sky).
        Box(Modifier.fillMaxSize().background(Jc.Ink.copy(alpha = if (Jc.Light) 0.66f else 0.35f)))
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 18.dp, end = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("JDVR", color = Jc.Focus, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Home", color = Jc.Muted, fontSize = 18.sp)
            Box(Modifier.weight(1f))
            tuners?.let { t ->
                if (t.capacity > 0) {
                    Text(
                        "${t.busy.size} of ${t.capacity} tuners in use",
                        color = Jc.Text,
                        fontSize = 14.sp,
                        modifier = Modifier.background(homePanel(), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            w?.let { x ->
                Text(
                    "${x.temperature}° ${x.label}" + (if (x.high != null && x.low != null) " · H ${x.high}° L ${x.low}°" else "") + (prefs.weatherPlace?.name?.substringBefore(',')?.let { " · $it" } ?: ""),
                    color = Jc.Text,
                    fontSize = 14.sp,
                    modifier = Modifier.background(homePanel(), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Text("${now.day()} · ${now.time()}", color = Jc.Text, fontSize = 16.sp)
        }

        // Back to live, and what's recording now.
        Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loaded && !hasLiveTv) {
                NoLiveTvCard(entry, Modifier.weight(1f).fillMaxHeight(), onSearch = { onOpenScreen(com.mackandall.jdvr.Screen.Search) })
            } else {
                LiveCard(last, fromStart, symbols, entry, Modifier.weight(1f).fillMaxHeight(), onPlayChannel, onPlayRecording, onGuide = { onOpenScreen(com.mackandall.jdvr.Screen.Guide) })
            }
            Column(
                Modifier.width(300.dp).fillMaxHeight().background(homePanel(), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("RECORDING NOW", color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val rec = upcoming.filter { it.recordingNow }
                if (rec.isEmpty()) Text("Nothing is recording.", color = Jc.Muted, fontSize = 14.sp)
                // Real recordings first; a live TV buffer is shown as one, not as a recording.
                // Three fit; the rest are a press away in Scheduled (Watch on each one recording now).
                val shown = if (rec.size > 3) 2 else 3
                rec.sortedBy { it.programId?.plainId() in bufferIds }.take(shown).forEach { r ->
                    val buffer = r.programId?.plainId() in bufferIds
                    SmallRow(
                        title = r.name,
                        detail = listOfNotNull(if (buffer) "Live TV buffer" else null, r.channelName, if (buffer) null else localOf(r.end)?.let { "until ${it.time()}" }).joinToString(" · "),
                        dot = !buffer,
                    ) {
                        // Watch it from the start; Back to live in the player tunes its channel.
                        scope.launch {
                            val pid = r.programId?.toUuid() ?: return@launch
                            val channel = runCatching { Jdvr.liveTv.program(pid)?.channelId }.getOrNull()
                            Jdvr.liveTv.recordingOfProgram(pid)?.let { onPlayRecording(PlayRecording(it, startMs = 0L, liveChannelId = channel)) }
                        }
                    }
                }
                if (rec.size > shown) {
                    SmallRow(title = "${rec.size - shown} more recording now", detail = "Open Scheduled", dot = false) {
                        onOpenScreen(com.mackandall.jdvr.Screen.Scheduled)
                    }
                }
            }
        }

        // New recordings.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (freshFromLibrary) "Recently added" else "New recordings", color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (freshFromLibrary) "unwatched · newest first · from your libraries" else "unwatched · newest first", color = Jc.Muted, fontSize = 13.sp)
            }
            val list = fresh
            when {
                list == null || !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
                list.isEmpty() -> Text("All caught up.", color = Jc.Muted, fontSize = 14.sp)
                else ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        list.forEach { c ->
                            NewCard(c, Modifier.weight(1f)) {
                                if (c.type == "Series") onOpenShow(c.id) else c.id.toUuid()?.let { onPlayRecording(PlayRecording(it)) }
                            }
                        }
                        repeat(5 - list.size) { Box(Modifier.weight(1f)) }
                    }
            }
        }

        // Tonight's schedule: chips of one size; any opens Scheduled.
        val tonight =
            upcoming.filter { !it.recordingNow && it.programId?.plainId() !in bufferIds }.filter { u -> localOf(u.start)?.let { Duration.between(now, it).toHours() < 8 } == true }.take(4)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tonight", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 4.dp))
            if (tonight.isEmpty()) Text("Nothing more scheduled.", color = Jc.Muted, fontSize = 14.sp)
            tonight.forEach { u ->
                HomeChip("${localOf(u.start)?.time() ?: ""}  ${u.name}" + (u.channelName?.let { " · $it" } ?: ""), Modifier.weight(1f, fill = false)) {
                    onOpenScreen(com.mackandall.jdvr.Screen.Scheduled)
                }
            }
            Box(Modifier.weight(0.01f))
            HomeChip("All scheduled ›") { onOpenScreen(com.mackandall.jdvr.Screen.Scheduled) }
        }
    }
    }
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
        modifier.background(homePanel(), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("BACK TO LIVE · LAST CHANNEL", color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(listOfNotNull(channel?.number, channel?.name).joinToString(" "), color = Jc.Muted, fontSize = 16.sp)
            Text(p?.name ?: channel?.name ?: "…", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Button(if (live) "Watch live" else "Live TV is off for this TV", primary = live, modifier = Modifier.focusRequester(entry)) { if (live) channel?.let { onPlayChannel(it.id) } }
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
    onClick: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
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
