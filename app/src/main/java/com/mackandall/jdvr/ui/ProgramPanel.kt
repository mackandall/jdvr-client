package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Buffer
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.EhProgram
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.MarkSymbols
import com.mackandall.jdvr.data.Program
import com.mackandall.jdvr.data.ScheduleState
import com.mackandall.jdvr.data.ShowForget
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** The selected programme: details and what can be done with it. */
@Composable
internal fun ProgramPanel(
    program: Program,
    channel: Channel?,
    symbols: MarkSymbols?,
    onWatch: () -> Unit,
    onWatchFromStart: (UUID) -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayItem: (com.mackandall.jdvr.PlayRecording) -> Unit = {},
    // When given, a successful action hands its message here (the caller closes the panel);
    // otherwise the panel shows it and stays open.
    onActed: ((String?) -> Unit)? = null,
    // "Other airings": the caller shows them (the guide, whose panel closes when focus leaves
    // it); without it the panel opens them itself.
    onOtherAirings: ((Program) -> Unit)? = null,
) {
    var p by remember(program.id) { mutableStateOf(program) }
    var airingsHere by remember(program.id) { mutableStateOf(false) }
    // Record series' options (Settings › Record series), shown over the panel.
    var seriesOptions by remember(program.id) { mutableStateOf(false) }
    // Episode History (watched mark, a copy to play, forget) and JellyDVR Live (is this only a buffer).
    var eh by remember(program.id) { mutableStateOf<EhProgram?>(null) }
    // What "Forget this show" would forget (administrators, a show with history).
    var showForget by remember(program.id) { mutableStateOf<ShowForget?>(null) }
    var buffer by remember(program.id) { mutableStateOf<Buffer?>(null) }
    var isBuffer by remember(program.id) { mutableStateOf(false) }
    var armed by remember(program.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(4000)
            armed = null
        }
    }
    // Being recorded now: the recording, to watch from the start (with commercial skipping).
    var recordingItem by remember(program.id) { mutableStateOf<UUID?>(null) }
    LaunchedEffect(program.id) {
        if (program.isRecording && program.isOn()) recordingItem = Jdvr.backend.recordingOfProgram(program.id)
    }
    var schedule by remember(program.id) { mutableStateOf<ScheduleState?>(null) }
    var note by remember(program.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var inside by remember(program.id) { mutableStateOf(false) }
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)

    suspend fun refresh() {
        Jdvr.backend.program(p.id)?.let { p = it }
        schedule = Jdvr.backend.scheduleState(p.id)
        eh = Jdvr.backend.ehProgram(p.id)
        showForget = if (p.isSeries) Jdvr.backend.forgetShow(p.id, dryRun = true) else null
        isBuffer = p.id.toString().plainId() in Jdvr.backend.bufferPrograms()
        buffer = Jdvr.backend.buffers(p.channelId).lastOrNull { it.programId?.plainId() == p.id.toString().plainId() }
    }
    LaunchedEffect(program.id) {
        eh = Jdvr.backend.ehProgram(program.id)
        if (program.isSeries) showForget = Jdvr.backend.forgetShow(program.id, dryRun = true)
        isBuffer = program.id.toString().plainId() in Jdvr.backend.bufferPrograms()
        if (isBuffer) buffer = Jdvr.backend.buffers(program.channelId).lastOrNull { it.programId?.plainId() == program.id.toString().plainId() }
        schedule = Jdvr.backend.scheduleState(program.id)
        // Opened as a floating dialog, its window needs a frame or two before focus can land.
        repeat(20) {
            if (inside) return@LaunchedEffect
            first.tryFocus()
            delay(50)
        }
    }

    fun act(block: suspend () -> String?) {
        scope.launch {
            val msg =
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("JDVR", "guide action failed", e)
                    note = "Didn't work: ${e.message ?: e.javaClass.simpleName}"
                    return@launch
                }
            val done = onActed
            if (done != null) {
                done(msg)
                return@launch
            }
            note = msg
            delay(1500) // the server schedules in the background
            refresh()
            onChanged()
        }
    }

    if (seriesOptions) {
        SeriesOptions(programId = program.id, programName = program.name) { msg ->
            seriesOptions = false
            if (msg != null) act { msg }
        }
    }

    // The panel is as wide as its two columns of buttons need; the header and note wrap to that.
    Column(
        modifier =
            modifier
                .onFocusChanged { inside = it.hasFocus }
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(22.dp),
    ) {
        FitToButtons(
            header = {
                Text(p.name, color = Jc.Title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                val episode = listOfNotNull(p.seasonEpisode, marks.title).joinToString(" · ")
                if (episode.isNotEmpty()) Text(episode, color = Jc.Text, fontSize = 16.sp)
                Text(
                    "${p.start.day()} · ${p.start.time()} – ${p.end.time()}" + (channel?.let { " · ${it.number} ${it.name}" } ?: ""),
                    color = Jc.Muted,
                    fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkChips(marks)
                    if (isBuffer) Chip("Live TV buffer", Jc.Muted)
                    if (eh?.markedWatched == true) Chip("marked watched", Jc.Green)
                }
                p.overview?.let { Text(it, color = Jc.Muted, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                schedule?.outcome?.let {
                    Text(
                        it.detail,
                        color = Jc.Amber,
                        fontSize = 14.sp,
                        modifier = Modifier.background(Jc.Amber.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(10.dp),
                    )
                }
                Spacer(4)
            },
            buttons = {
                // What this TV may not do (JellyDVR Space's device rights) is named, not offered; the
                // line keeps the panel's first focus.
                val rights = deviceRights()
                // Two columns, so every action stays on the screen: watching and recording on the left,
                // the rest on the right.
                MenuColumns(
                    fit = true,
                    left = {
                        if (p.isOn()) {
                            if (rights.liveTv) {
                                Button("Watch live", modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = onWatch)
                            } else {
                                Button("Live TV is off for this TV", modifier = Modifier.fillMaxWidth().focusRequester(first)) {}
                            }
                        }
                        recordingItem?.let { id -> Button("Watch from start", modifier = Modifier.fillMaxWidth(), leading = { Text("⏮", color = Jc.Text) }) { onWatchFromStart(id) } }
                        // A copy Episode History knows about (recorded before, or in a library).
                        eh?.libraryItemId?.toUuid()?.let { lib ->
                            val resume = eh?.libraryItemPositionTicks?.div(10_000L) ?: 0L
                            Button(if (resume > 60_000) "Play from library · ${resume / 60_000} min in" else "Play from library", modifier = Modifier.fillMaxWidth(), leading = { Text("▶", color = Jc.Text) }) {
                                onPlayItem(com.mackandall.jdvr.PlayRecording(lib))
                            }
                        }
                        val recordModifier = if (p.isOn()) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().focusRequester(first)
                        val bufferNow = buffer
                        if (!rights.record) {
                            Button("Recording is off for this TV", modifier = recordModifier) {}
                        } else if (isBuffer && bufferNow != null && !bufferNow.kept) {
                            // Only a buffer so far: Record keeps it (recorded from when it started).
                            Button("Record (keep the live buffer)", modifier = recordModifier, leading = { Dot(Jc.Recording) }) {
                                act {
                                    Jdvr.backend.keepBuffer(bufferNow.id, true)
                                    "Recording: the live TV buffer is kept."
                                }
                            }
                        } else if (p.isRecording && !isBuffer) {
                            // Under way it stops (what's recorded so far is kept); later, it won't record.
                            Button(if (p.isOn()) "Stop recording" else "Cancel recording", modifier = recordModifier, leading = { if (p.isOn()) Dot(Jc.Recording) else CancelDot() }) {
                                act {
                                    Jdvr.backend.cancelRecording(p.timerId!!)
                                    if (p.isOn()) "Recording stopped: what was recorded so far is kept." else "Recording cancelled."
                                }
                            }
                        } else {
                            Button("Record", modifier = recordModifier, leading = { Dot(Jc.Recording) }) {
                                act {
                                    Jdvr.backend.record(p.id, series = false)
                                    "Recording scheduled."
                                }
                            }
                        }
                        if (p.isSeries && rights.record) {
                            if (p.seriesTimerId != null) {
                                Button("Cancel series recording", modifier = Modifier.fillMaxWidth(), leading = { SeriesDots(height = 14.dp, crossed = true) }) {
                                    act {
                                        Jdvr.backend.cancelSeries(p.seriesTimerId!!)
                                        "Series recording cancelled."
                                    }
                                }
                            } else {
                                Button("Record series", modifier = Modifier.fillMaxWidth(), leading = { SeriesDots() }) {
                                    if (Jdvr.prefs.seriesOptionsFirst) {
                                        seriesOptions = true
                                        return@Button
                                    }
                                    act {
                                        Jdvr.backend.record(p.id, series = true)
                                        "Series recording set up."
                                    }
                                }
                            }
                        }
                        if (schedule?.outcome != null && rights.record) {
                            Button("Record this instead", modifier = Modifier.fillMaxWidth(), leading = { Dot(Jc.Amber) }) {
                                act { Jdvr.backend.recordInstead(p.id)?.message }
                            }
                        }
                    },
                    right = {
                        // Every later airing of it, with tuner clashes and commercial-free channels (Episode History).
                        if (eh != null) {
                            Button("Other airings", modifier = Modifier.fillMaxWidth()) {
                                if (onOtherAirings != null) onOtherAirings(p) else airingsHere = true
                            }
                        }
                        if (airingsHere) {
                            AiringsDialog(programId = p.id, onNote = { msg -> if (onActed != null) onActed(msg) }, onDismiss = { airingsHere = false })
                        }
                        if (schedule != null && rights.record) {
                            val fav = schedule?.favourite
                            Button(
                                if (fav == null) "Add to Recording Favourites" else "Remove from Recording Favourites",
                                modifier = Modifier.fillMaxWidth(),
                                leading = { Text(if (fav == null) "☆" else "★", color = Jc.Gold, fontSize = 18.sp) },
                            ) {
                                if (fav != null && armed != "fav") {
                                    armed = "fav"
                                } else {
                                    armed = null
                                    act { (if (fav == null) Jdvr.backend.addFavourite(p.id) else Jdvr.backend.removeFavourite(fav.id))?.message }
                                }
                            }
                            if (armed == "fav") Text("Press again to remove it from Recording Favourites", color = Jc.Amber, fontSize = 13.sp)
                        }
                        eh?.let { info ->
                            Button(if (info.markedWatched) "Unmark watched" else "Mark watched", modifier = Modifier.fillMaxWidth(), leading = { Text("✓", color = Jc.Green) }) {
                                act {
                                    Jdvr.backend.setProgramWatched(p.id, !info.markedWatched)
                                    if (info.markedWatched) "No longer marked watched." else "Marked watched: every airing of this episode shows it."
                                }
                            }
                            if (info.canForget) {
                                Button(if (armed == "forget") "Press again to forget" else "Forget this episode", modifier = Modifier.fillMaxWidth()) {
                                    if (armed != "forget") {
                                        armed = "forget"
                                    } else {
                                        armed = null
                                        act {
                                            Jdvr.backend.forgetProgram(p.id)
                                            "Forgotten: it can be recorded again."
                                        }
                                    }
                                }
                            }
                        }
                        showForget?.takeIf { it.episodes > 0 }?.let { sf ->
                            Button(if (armed == "forgetShow") "Press again: forget all of ${sf.show ?: "this show"}" else "Forget this show (${sf.episodes} episode${if (sf.episodes == 1) "" else "s"})", modifier = Modifier.fillMaxWidth()) {
                                if (armed != "forgetShow") {
                                    armed = "forgetShow"
                                } else {
                                    armed = null
                                    act {
                                        val r = Jdvr.backend.forgetShow(p.id, dryRun = false)
                                        if (r == null) "Couldn't forget it." else "Forgot ${sf.show ?: "the show"}: its episodes can be recorded again" + if (r.keptInLibrary > 0) " (copies still in the library still count)." else "."
                                    }
                                }
                            }
                        }
                    },
                )
            },
            footer = {
                note?.let { Text(it, color = Jc.Muted, fontSize = 14.sp) }
            },
        )
    }
}

/**
 * The header over two columns of buttons and a note under them, the header and note as wide as
 * the buttons (at least 360 dp), so the panel takes only the room its buttons need.
 */
@Composable
private fun FitToButtons(
    header: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { header() }
        Box { buttons() }
        Column { footer() }
    }) { m, c ->
        val gap = 10.dp.roundToPx()
        val b =
            m[1].measure(
                androidx.compose.ui.unit
                    .Constraints(maxWidth = c.maxWidth),
            )
        val w = maxOf(b.width, 360.dp.roundToPx()).coerceAtMost(c.maxWidth)
        val fixed =
            androidx.compose.ui.unit
                .Constraints(minWidth = w, maxWidth = w)
        val h = m[0].measure(fixed)
        val f = m[2].measure(fixed)
        val footerAt = h.height + gap + b.height + gap
        layout(w, if (f.height > 0) footerAt + f.height else h.height + gap + b.height) {
            h.place(0, 0)
            b.place(0, h.height + gap)
            if (f.height > 0) f.place(0, footerAt)
        }
    }
}

/**
 * Where a programme's actions open (Settings › Guide › Programme actions): docked on the right,
 * full height, or floating in the middle over the dimmed screen like the other menus. Back, or
 * focus leaving it, closes it ([onDismiss]).
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ProgramActionsHost(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (Jdvr.prefs.programActions == com.mackandall.jdvr.data.Prefs.FLOATING) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties =
                androidx.compose.ui.window
                    .DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Jc.Sheet)
                    .padding(6.dp),
            ) { content() }
        }
    } else {
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(Jc.Sheet)) { content() }
    }
}
