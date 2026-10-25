package com.mackandall.jdvr.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.ProgramStartResult
import com.mackandall.jdvr.data.ProgramStartStatus
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.data.Editing
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** A keyframe a cut can start on, with its picture; [before]: at or before the paused moment. */
internal data class KeyChoice(
    val seconds: Double,
    val picture: ImageBitmap?,
    val before: Boolean,
)

/**
 * Editing the recording (Comskip plugin; users allowed to edit recordings): while paused, "✂ Edit"
 * opens the actions, and each acts on a second press that says what it will do.
 */
@Stable
internal class RecordingEditor(
    private val itemId: UUID,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    // A message over the picture (null clears it).
    private val say: (String?) -> Unit,
    // The recording's markers changed: show them again.
    private val reloadSegments: suspend () -> Unit,
    private val closeControls: () -> Unit,
    private val playFocus: FocusRequester,
) {
    var open by mutableStateOf(false)
        private set

    // Which action is waiting for its second press.
    var armed by mutableStateOf<String?>(null)
        private set

    // "Starts here" with trims: the keyframes either side of the paused moment, to pick the one the
    // cut starts on (a cut can only start on a keyframe).
    var keyChoices by mutableStateOf<List<KeyChoice>?>(null)
        private set
    var startStatus by mutableStateOf<ProgramStartStatus?>(null)
        private set

    // One at a time: the server refuses a second action on the same recording anyway.
    var busy by mutableStateOf(false)
        private set

    // Opening or closing the panel removes the focused button: focus goes to the first action, and
    // back to Edit on Close (otherwise the remote reaches nothing).
    val first = FocusRequester()
    val editButton = FocusRequester()
    var focusNext by mutableStateOf<FocusRequester?>(null)

    fun openRow() {
        open = true
        focusNext = first
    }

    fun close() {
        open = false
        armed = null
        keyChoices = null
        focusNext = editButton
    }

    /** First press arms [action]; true on the second, which should then act. */
    fun pressed(action: String): Boolean {
        if (armed != action) {
            armed = action
            return false
        }
        armed = null
        return true
    }

    fun disarm() {
        armed = null
    }

    // Back with the edit row open closes it, as Close does (the pictures first, if they're up).
    fun back() {
        if (keyChoices != null) {
            keyChoices = null
            focusNext = first
        } else {
            close()
        }
    }

    fun cancelChoices() {
        keyChoices = null
        focusNext = first
    }

    /** Playing again: the row goes away. */
    fun playing() {
        open = false
        armed = null
        keyChoices = null
    }

    suspend fun loadStatus() {
        startStatus = attempt { Jdvr.backend.programStartStatus(itemId) }
    }

    // What an action that changed the recording's markers or file did, for 7 s.
    private suspend fun report(
        r: ProgramStartResult?,
        fallback: String,
    ) {
        say(
            when {
                r == null -> fallback
                r.trimmed -> r.message + " Start the recording again to see it."
                else -> r.message
            },
        )
        if (r?.ok == true && !r.trimmed) reloadSegments()
        com.mackandall.jdvr.ui.MoveWatcher.kick()
        delay(7000)
        say(null)
    }

    // An edit action from the row: it closes, and focus goes back to Play.
    private fun done(
        fallback: String,
        action: suspend () -> ProgramStartResult?,
    ) {
        open = false
        focusNext = playFocus
        scope.launch { report(action(), fallback) }
    }

    fun clearStart() = done("Couldn't remove it.") { attempt { Jdvr.backend.clearProgramStart(itemId) } }

    fun undoTrim() = done("Couldn't undo it.") { attempt { Jdvr.backend.undoTrim(itemId) }?.let { it.copy(trimmed = it.ok) } }

    // Program start: paused on the program's first frame, or on the channel's card before its
    // programs (kept, so later recordings find their start by themselves).
    fun programStart(card: Boolean) {
        if (busy) return
        busy = true
        open = false
        val at = player.currentPosition / 1000.0
        closeControls()
        scope.launch {
            say(if (card) "Looking at the card…" else "Setting the program start…")
            val r = attempt { if (card) Jdvr.backend.rememberStartCard(itemId, at) else Jdvr.backend.setProgramStart(itemId, at) }
            busy = false
            report(r, "Couldn't do it: something may already be working on this recording.")
        }
    }

    // Finds the keyframes either side of the paused moment and offers them, with their pictures.
    fun chooseKeyframe() {
        if (busy) return
        busy = true
        val at = player.currentPosition / 1000.0
        scope.launch {
            say("Finding where a cut can start…")
            val k = attempt { Jdvr.backend.keyframes(itemId, at) }
            val times = listOfNotNull(k?.before, k?.after)
            if (times.isEmpty()) {
                busy = false
                say("Couldn't find where a cut can start near here.")
                delay(5000)
                say(null)
                return@launch
            }
            val pictures = times.map { t -> async { attempt { Jdvr.backend.frame(itemId, t) } } }.map { it.await() }
            keyChoices = times.mapIndexed { i, t -> KeyChoice(t, pictures[i], before = t <= at + 0.02) }
            busy = false
            say(null)
            focusNext = first
        }
    }

    fun startAtKeyframe(seconds: Double) {
        keyChoices = null
        open = false
        closeControls()
        busy = true
        scope.launch {
            say("Setting the program start…")
            val r = attempt { Jdvr.backend.setProgramStart(itemId, seconds, keyframe = true) }
            busy = false
            report(r, "Couldn't do it: something may already be working on this recording.")
        }
    }
}

/** The editor's state's side effects: focus moves, the armed press timing out, the status, playing. */
@Composable
internal fun RecordingEditor.Effects(isPlaying: Boolean) {
    LaunchedEffect(focusNext) {
        val target = focusNext ?: return@LaunchedEffect
        repeat(20) {
            if (target.tryFocus()) {
                focusNext = null
                return@LaunchedEffect
            }
            delay(50)
        }
        focusNext = null
    }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(4000)
            disarm()
        }
    }
    LaunchedEffect(open) { if (open) loadStatus() }
    LaunchedEffect(isPlaying) { if (isPlaying) playing() }
}

/**
 * The ✂ Edit line under the controls: the button, then the actions (each pressed twice), or the
 * keyframe pictures to start a cut on.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditRow(
    editor: RecordingEditor,
    edit: Editing,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        // Back taken on the row itself: Compose's own Back on a focused button cleared focus first,
        // leaving the controls up with nothing focused, and the remote reached nothing until a second Back.
        modifier =
            Modifier.onPreviewKeyEvent { e ->
                if (!editor.open || e.key != Key.Back) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) editor.back()
                true
            },
    ) {
        val choices = editor.keyChoices
        when {
            !editor.open -> Button("✂ Edit", modifier = Modifier.focusRequester(editor.editButton)) { editor.openRow() }
            choices != null -> KeyframeChoices(editor, choices, edit.undoNote)
            else -> EditActions(editor, edit)
        }
    }
}

@Composable
private fun KeyframeChoices(
    editor: RecordingEditor,
    choices: List<KeyChoice>,
    undoNote: String,
) {
    val spacing = if (choices.size == 2) " (every %.1f s here)".format(choices[1].seconds - choices[0].seconds) else ""
    Text(
        "A cut can only start on a keyframe$spacing. Pick the picture the recording will start on; everything before it is cut. $undoNote",
        color = Jc.Text,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth(),
    )
    // The two pictures and Cancel on one line, so all of them stay on the screen.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        choices.forEachIndexed { i, c ->
            Button(
                label = clockTenths(c.seconds) + if (c.before) "\nbefore" else "\nafter",
                modifier = if (i == 0) Modifier.focusRequester(editor.first) else Modifier,
                leading = {
                    val picture = c.picture
                    if (picture != null) {
                        androidx.compose.foundation.Image(picture, contentDescription = null, modifier = Modifier.width(176.dp).height(99.dp))
                    }
                },
            ) { editor.startAtKeyframe(c.seconds) }
        }
        Button("Cancel") { editor.cancelChoices() }
    }
}

@Composable
private fun EditActions(
    editor: RecordingEditor,
    edit: Editing,
) {
    val armed = editor.armed
    Button(
        modifier = Modifier.focusRequester(editor.first),
        label =
            when {
                edit.programStartTrims -> "Starts here (this recording)…"
                armed != "start" -> "Starts here (this recording)"
                else -> "Press again: skip to here from the start"
            },
    ) {
        if (edit.programStartTrims) editor.chooseKeyframe() else if (editor.pressed("start")) editor.programStart(card = false)
    }
    Button(if (armed == "card") "Press again: remember this card for this channel's recordings" else "Remember card (all recordings)") {
        if (editor.pressed("card")) editor.programStart(card = true)
    }
    val ps = editor.startStatus
    val start = ps?.startSeconds
    if (ps?.action == "marker" && start != null && start >= 1) {
        Button(if (armed == "clear") "Press again: play from the very start again" else "Remove program start (skips to ${clock((start * 1000).toLong())})") {
            if (editor.pressed("clear")) editor.clearStart()
        }
    }
    if (ps?.canUndo == true) {
        Button(if (armed == "undo") "Press again: put the original recording back" else "Undo trim (put the original back)") {
            if (editor.pressed("undo")) editor.undoTrim()
        }
    }
    Button("Close") { editor.close() }
}

internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** 1:04.1 */
internal fun clockTenths(seconds: Double): String {
    val tenths = (seconds * 10).toLong()
    val s = tenths / 10
    return (if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)) + ".${tenths % 10}"
}
