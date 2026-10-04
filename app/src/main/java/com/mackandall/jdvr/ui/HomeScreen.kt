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
    var tuners by remember { mutableStateOf<LiveTvRepository.TunerStatus?>(null) }
    var bufferIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val scope = rememberCoroutineScope()
    val symbols = rememberMarkSymbols()

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val channels = runCatching { Jdvr.liveTv.channels() }.getOrDefault(emptyList())
            last = channels.firstOrNull { it.id.toString() == Jdvr.session.lastChannelId } ?: channels.firstOrNull()
            fromStart = last?.now?.takeIf { it.isRecording }?.let { Jdvr.liveTv.recordingOfProgram(it.id) }
            upcoming = Jdvr.liveTv.upcoming().orEmpty()
            bufferIds = Jdvr.liveTv.bufferPrograms()
            fresh = Jdvr.liveTv.recordedShows()?.filter { it.unplayedCount > 0 }?.take(5)
            tuners = Jdvr.liveTv.tuners(null)
            delay(60_000)
        }
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
                        modifier = Modifier.background(Jc.Panel, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            Text("${now.day()} · ${now.time()}", color = Jc.Text, fontSize = 16.sp)
        }

        // Back to live, and what's recording now.
        Row(Modifier.fillMaxWidth().height(196.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LiveCard(last, fromStart, symbols, entry, Modifier.weight(1f).fillMaxHeight(), onPlayChannel, onPlayRecording, onGuide = { onOpenScreen(com.mackandall.jdvr.Screen.Guide) })
            Column(
                Modifier.width(300.dp).fillMaxHeight().background(Jc.Panel, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("RECORDING NOW", color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val rec = upcoming.filter { it.recordingNow }
                if (rec.isEmpty()) Text("Nothing is recording.", color = Jc.Muted, fontSize = 14.sp)
                // Real recordings first; a live TV buffer is shown as one, not as a recording.
                rec.sortedBy { it.programId?.plainId() in bufferIds }.take(3).forEach { r ->
                    val buffer = r.programId?.plainId() in bufferIds
                    SmallRow(
                        title = r.name,
                        detail = listOfNotNull(if (buffer) "Live TV buffer" else null, r.channelName, if (buffer) null else localOf(r.end)?.let { "until ${it.time()}" }).joinToString(" · "),
                        dot = !buffer,
                    ) {
                        // Watch it from the start.
                        scope.launch { r.programId?.toUuid()?.let { Jdvr.liveTv.recordingOfProgram(it) }?.let { onPlayRecording(PlayRecording(it, startMs = 0L)) } }
                    }
                }
            }
        }

        // New recordings.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New recordings", color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("unwatched · newest first", color = Jc.Muted, fontSize = 13.sp)
            }
            val list = fresh
            when {
                list == null -> Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
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
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
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
        modifier.background(Jc.Panel, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
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
            Button("Watch live", primary = true, modifier = Modifier.focusRequester(entry)) { channel?.let { onPlayChannel(it.id) } }
            if (fromStart != null) {
                Button("Watch from start") { onPlayRecording(PlayRecording(fromStart, startMs = 0L, liveChannelId = channel?.id)) }
            }
            Button("Guide", onClick = onGuide)
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
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
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
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
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
