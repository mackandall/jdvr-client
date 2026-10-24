package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private fun localTime(iso: String): LocalDateTime? = runCatching { LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault()) }.getOrNull()

/**
 * Scheduled recordings, as a compact list grouped by day (JellyDVR Schedule's fast list): what will
 * record, when, on which channel, and why (series / Recording Favourite / one-off). OK on one offers
 * "Cancel recording".
 */
@Composable
fun ScheduledScreen(
    entry: FocusRequester,
    onPlayRecording: (com.mackandall.jdvr.PlayRecording) -> Unit = {},
) {
    val watchLive = LocalWatchLive.current
    var items by remember { mutableStateOf<List<LiveTvRepository.Upcoming>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var bufferIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        items = Jdvr.liveTv.schedule()
        bufferIds = Jdvr.liveTv.bufferPrograms()
        loaded = true
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Scheduled", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            items?.let { Text("${it.size} recording" + (if (it.size == 1) "" else "s"), color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            note?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Spacer(12)
        val list = items
        when {
            !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            list == null -> Text("Couldn't load the schedule from the server.", color = Jc.Amber, fontSize = 16.sp)
            list.isEmpty() -> Text("Nothing scheduled.", color = Jc.Muted, fontSize = 16.sp)
            else -> {
                val byDay = list.groupBy { localTime(it.start)?.toLocalDate() }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    byDay.forEach { (day, rows) ->
                        item(key = "day-$day") {
                            Text(
                                (localTime(rows.first().start)?.day() ?: "") + " (${rows.size})",
                                color = Jc.Text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { it.timerId }) { r ->
                            ScheduleRow(
                                r = r,
                                expanded = open == r.timerId,
                                buffer = r.programId?.plainId() in bufferIds,
                                modifier = if (r == list.first()) Modifier.focusRequester(entry) else Modifier,
                                onToggle = { open = if (open == r.timerId) null else r.timerId },
                                onNote = { msg ->
                                    note = msg
                                    scope.launch {
                                        delay(1500) // the server schedules in the background
                                        reload++
                                    }
                                },
                                // Recording now: its file so far (Back to live in the player), or its channel.
                                onWatch = { fromStart ->
                                    scope.launch {
                                        val program = r.programId?.toUuid()?.let { runCatching { Jdvr.liveTv.program(it) }.getOrNull() }
                                        val item = if (fromStart) program?.id?.let { runCatching { Jdvr.liveTv.recordingOfProgram(it) }.getOrNull() } else null
                                        when {
                                            fromStart && item != null -> onPlayRecording(com.mackandall.jdvr.PlayRecording(item, startMs = 0L, liveChannelId = program?.channelId))
                                            !fromStart && program != null && watchLive != null -> watchLive(program.channelId)
                                            else -> note = "Couldn't find it on the server (it may have just ended)."
                                        }
                                    }
                                },
                                onCancel = {
                                    scope.launch {
                                        note =
                                            runCatching {
                                                Jdvr.liveTv.cancelRecording(r.timerId)
                                                "Won't record ${r.name} at ${localTime(r.start)?.time()}."
                                            }.getOrElse { "Couldn't cancel: ${it.message}" }
                                        open = null
                                        delay(800)
                                        reload++
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    r: LiveTvRepository.Upcoming,
    expanded: Boolean,
    buffer: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onWatch: (fromStart: Boolean) -> Unit,
    onNote: (String) -> Unit = {},
) {
    val start = localTime(r.start)
    val end = localTime(r.end)
    Column {
        Surface(
            onClick = onToggle,
            modifier = modifier.fillMaxWidth().height(52.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${start?.time() ?: ""} – ${end?.time() ?: ""}", color = Jc.Text, fontSize = 15.sp, modifier = Modifier.width(170.dp))
                Text(listOfNotNull(r.channelNumber, r.channelName).joinToString(" "), color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                Text(
                    r.name + (listOfNotNull(r.seasonEpisode, r.episodeTitle).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (r.recordingNow && !buffer) Chip("● Recording", Jc.Recording)
                if (buffer) Chip("Live TV buffer", Jc.Muted)
                else when (r.kind) {
                    "favourite" -> Chip("★ ${r.favourite ?: "Favourite"}", Jc.Gold)
                    "series" -> Chip("Series", Jc.Muted)
                    else -> Chip("One-off", Jc.Muted)
                }
                if (r.isRepeat) Chip("Repeat", Jc.Muted)
            }
        }
        if (expanded) {
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Only a recording in progress has something to watch.
                if (r.recordingNow) {
                    Button(if (buffer) "Watch the buffer from the start" else "Watch from the start", leading = { Text("⏮", color = Jc.Text) }) { onWatch(true) }
                    Button("Watch live", leading = { Text("▶", color = Jc.Text) }) { onWatch(false) }
                }
                // Only on a TV allowed to record (JellyDVR Space's device rights).
                // Only on a TV allowed to record (JellyDVR Space's device rights). Stopping a recording
                // under way asks for a second press, like Stop recording elsewhere.
                if (deviceRights().record) {
                    var armed by remember(r.timerId) { mutableStateOf(false) }
                    LaunchedEffect(armed) {
                        if (armed) {
                            delay(4000)
                            armed = false
                        }
                    }
                    Button(
                        when {
                            !r.recordingNow -> "Cancel recording"
                            armed -> "Press again to stop recording"
                            else -> "Stop this recording"
                        },
                        leading = { Dot(Jc.Recording) },
                    ) {
                        if (r.recordingNow && !armed) {
                            armed = true
                        } else {
                            armed = false
                            onCancel()
                        }
                    }
                }
                // Another airing of it instead (fewer tuners busy, commercial-free); Episode History.
                if (!r.recordingNow && r.programId != null && !buffer) {
                    var airings by remember(r.timerId) { mutableStateOf(false) }
                    Button("Other airings") { airings = true }
                    if (airings) {
                        r.programId.toUuid()?.let { pid ->
                            AiringsDialog(programId = pid, onNote = onNote, onDismiss = { airings = false })
                        }
                    }
                }
                Button("Keep it", onClick = onToggle)
            }
        }
    }
}
