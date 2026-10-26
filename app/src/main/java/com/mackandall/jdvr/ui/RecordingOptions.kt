package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.ShowStorage
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.TimerInfoDto
import java.util.UUID

/**
 * One recording's options, as in the web client's Recording options: start early, end late, and
 * (JellyDVR Space) where this one recording is stored. The timer is [timerId], for guide programme
 * [programId]. Store in saves on the picker's own Save; Save here sends the padding. [onDone] gets
 * the message to show, or null when nothing was saved.
 */
@Composable
fun RecordingOptions(
    timerId: String,
    programId: UUID,
    programName: String,
    onDone: (String?) -> Unit,
) {
    var loaded by remember { mutableStateOf<TimerInfoDto?>(null) }
    var timer by remember { mutableStateOf<TimerInfoDto?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var stored by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val rights = deviceRights()
    var storage by remember { mutableStateOf<ShowStorage?>(null) }
    var pickingStore by remember { mutableStateOf(false) }

    suspend fun loadStorage() {
        storage = if (rights.archive) attempt { Jdvr.backend.showStorage(programId) }?.takeIf { it.problem == null && it.folders.isNotEmpty() } else null
    }
    LaunchedEffect(rights.archive) { loadStorage() }
    LaunchedEffect(Unit) {
        try {
            val t = Jdvr.backend.timer(timerId) ?: error("It's no longer scheduled")
            loaded = t
            timer = t
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't load the options: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    LaunchedEffect(timer != null, problem) { first.focusSoon() }

    Dialog(onDismissRequest = { onDone(stored) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides false) {
            Column(
                Modifier
                    .width(820.dp)
                    .heightIn(max = 500.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Jc.Sheet)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val t = timer
                Text(t?.name?.ifEmpty { null } ?: programName, color = Jc.Title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Recording options · OK changes a row · Back cancels", color = Jc.Muted, fontSize = 14.sp)
                problem?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
                if (t == null) {
                    if (problem == null) Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                    Button("Close", modifier = Modifier.focusRequester(first)) { onDone(stored) }
                    return@Column
                }
                EditRow("Start early", "Begin recording before the guide's start time.", padding(t.prePaddingSeconds, "early") ?: "On time", Modifier.focusRequester(first)) {
                    timer = t.copy(prePaddingSeconds = nextOf(PrePad, PrePad.minByOrNull { kotlin.math.abs(it - (t.prePaddingSeconds ?: 0)) }!!))
                }
                EditRow("End late", "Keep recording after the guide's end time (for overruns).", padding(t.postPaddingSeconds, "late") ?: "On time") {
                    timer = t.copy(postPaddingSeconds = nextOf(PostPad, PostPad.minByOrNull { kotlin.math.abs(it - (t.postPaddingSeconds ?: 0)) }!!))
                }
                storage?.let { st ->
                    val (where, help) = st.oneRecordingSummary()
                    EditRow("Store in", help, where) { pickingStore = true }
                    if (pickingStore) {
                        ShowStoragePicker(
                            st,
                            itemId = programId,
                            oneOnly = true,
                            onSaved = { msg ->
                                pickingStore = false
                                stored = msg
                                scope.launch { loadStorage() }
                            },
                            onDismiss = { pickingStore = false },
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(if (saving) "Saving…" else "Save", primary = true) {
                        if (saving) return@Button
                        if (t == loaded) {
                            onDone(stored)
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            try {
                                Jdvr.backend.updateTimer(t)
                                onDone("Recording options saved." + (stored?.let { " $it" } ?: ""))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("JDVR", "recording options failed", e)
                                problem = "Didn't work: ${e.message ?: e.javaClass.simpleName}"
                                saving = false
                            }
                        }
                    }
                    Button("Cancel") { onDone(stored) }
                }
            }
        }
    }
}

/** Where this one recording goes, and the help line under it: its own choice, else the show's. */
private fun ShowStorage.oneRecordingSummary(): Pair<String, String> {
    val own = recording
    val showWhere = labels.takeIf { it.isNotEmpty() }?.joinToString(", then ") ?: "the recording drive"
    return when (own?.mode) {
        "drive" -> "The recording drive" to "This recording only · the show's setting is $showWhere"
        "folders" -> labelsOf(own.locations).joinToString(", then ") to "This recording only · the show's setting is $showWhere"
        else -> "The show's setting · $showWhere" to "Where this recording goes when it's done (JellyDVR Space)."
    }
}
