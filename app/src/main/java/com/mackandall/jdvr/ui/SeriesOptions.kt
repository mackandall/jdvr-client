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
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import java.util.UUID

/**
 * Record series with its options first: the server's defaults for [programId] (which episodes,
 * channel, time, padding, how many to keep), changed row by row, then set up. [onDone] gets the
 * message to show, or null when cancelled.
 */
@Composable
fun SeriesOptions(
    programId: UUID,
    programName: String,
    onDone: (String?) -> Unit,
) {
    var timer by remember { mutableStateOf<SeriesTimerInfoDto?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // JellyDVR Space: where the show's recordings go, saved with the series.
    val rights = deviceRights()
    var storage by remember { mutableStateOf<ShowStorage?>(null) }
    var storeIn by remember { mutableStateOf<List<String>?>(null) }
    var pickingStore by remember { mutableStateOf(false) }
    LaunchedEffect(rights.archive) {
        storage = if (rights.archive) attempt { Jdvr.backend.showStorage(programId) }?.takeIf { it.problem == null && it.folders.isNotEmpty() } else null
    }

    LaunchedEffect(Unit) {
        try {
            timer = Jdvr.backend.newSeriesTimer(programId) ?: error("This server doesn't offer series options")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't load the options: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    // Focus on the first row once the options are in (not on Loading's Close, which then goes);
    // the dialog's window needs a frame or two before focus can land.
    LaunchedEffect(timer != null, problem) {
        first.focusSoon()
    }

    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides false) {
            Column(
                Modifier
                    .width(820.dp)
                    // The TV is 960×540 dp: scrolls to the buttons rather than running off the bottom.
                    .heightIn(max = 500.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Jc.Sheet)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val t = timer
                Text(t?.name?.ifEmpty { null } ?: programName.ifEmpty { "Series recording" }, color = Jc.Title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("Record series · OK changes a row · Back cancels", color = Jc.Muted, fontSize = 14.sp)
                problem?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
                if (t == null) {
                    if (problem == null) Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                    Button("Close", modifier = Modifier.focusRequester(first)) { onDone(null) }
                    return@Column
                }
                SeriesTimerRows(t, first) { timer = it }
                storage?.let { st ->
                    val now = st.copy(locations = storeIn ?: st.locations)
                    EditRow("Store in", "Where finished recordings go (JellyDVR Space's storage folders).", now.summary() ?: "The recording drive") {
                        pickingStore = true
                    }
                    if (pickingStore) {
                        ShowStoragePicker(
                            now,
                            onSaved = { _, _ -> pickingStore = false },
                            onDismiss = { pickingStore = false },
                            onPicked = {
                                storeIn = it
                                pickingStore = false
                            },
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(if (saving) "Saving…" else "Record series", primary = true, leading = { SeriesDots() }) {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            try {
                                Jdvr.backend.createSeriesTimer(t)
                                val st = storage
                                val picked = storeIn
                                val stored =
                                    if (st != null && picked != null && picked != st.locations) {
                                        attempt { Jdvr.backend.setShowStorage(st.show, picked) }?.message
                                    } else {
                                        null
                                    }
                                onDone("Series recording set up." + (stored?.let { " $it" } ?: ""))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("JDVR", "series options failed", e)
                                problem = "Didn't work: ${e.message ?: e.javaClass.simpleName}"
                                saving = false
                            }
                        }
                    }
                    Button("Cancel") { onDone(null) }
                }
            }
        }
    }
}
