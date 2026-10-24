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
import com.mackandall.jdvr.data.NextPvrBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

// NextPVR's recurring types, in its web page's order and words.
private val Types =
    listOf(
        1 to "New episodes",
        2 to "All episodes",
        3 to "Daily, this timeslot",
        4 to "Weekly, this timeslot",
        5 to "Monday to Friday, this timeslot",
        6 to "Weekends, this timeslot",
        7 to "All episodes, all channels",
    )
internal val KeepChoices = listOf(0, 1, 2, 3, 5, 10, 20)
internal val PreChoices = listOf(0, 1, 2, 3, 5, 10, 15)
internal val PostChoices = listOf(0, 1, 2, 3, 5, 10, 15, 30, 60)

internal fun <T> stepFrom(
    list: List<T>,
    current: T,
): T = list[(list.indexOf(current) + 1).mod(list.size)]

internal fun nearest(
    list: List<Int>,
    value: Int,
): Int = list.minByOrNull { kotlin.math.abs(it - value) } ?: value

internal fun minutes(
    m: Int,
    word: String,
    default: Int,
): String = (if (m == 0) "On time" else "$m min $word") + if (m == default) " (server default)" else ""

// When a timeslot rule records: "every day at 8:30 AM", "Saturdays at 8:30 AM"…
private fun slot(
    type: Int,
    start: java.time.LocalDateTime,
): String {
    val at = "at " + start.time()
    return when (type) {
        3 -> "every day $at"
        4 -> start.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + "s $at"
        5 -> "Monday to Friday $at"
        else -> "weekends $at"
    }
}

/**
 * NextPVR's series options, as its web page's Advanced record: which airings (new, all, a
 * timeslot, all channels), how many to keep, padding, folder, and on an existing series whether
 * it's on. Opened by Record series ([programId]) or the Series screen's Edit ([ruleId]); OK on a
 * row steps through its choices, Save sends them. [onDone] gets the message to show, or null
 * when cancelled.
 */
@Composable
fun NextPvrSeriesOptions(
    onDone: (String?) -> Unit,
    programId: UUID? = null,
    programName: String = "",
    channelId: UUID? = null,
    channelName: String? = null,
    programStart: java.time.LocalDateTime? = null,
    ruleId: String? = null,
) {
    val backend = Jdvr.nextPvrBackend ?: return
    var defaults by remember { mutableStateOf<NextPvrBackend.SeriesDefaults?>(null) }
    var rule by remember { mutableStateOf<NextPvrBackend.SeriesRule?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            val d = backend.seriesDefaults()
            defaults = d
            rule =
                if (ruleId != null) {
                    backend.seriesRule(ruleId) ?: error("That series isn't on the server any more")
                } else {
                    backend.newSeriesRule(programId!!, programName, channelId, channelName, programStart, d)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't load the options: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    // Focus on the first row once the options are in (not on Loading's Close, which then goes);
    // the dialog's window needs a frame or two before focus can land.
    LaunchedEffect(rule != null, problem) {
        repeat(20) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides false) {
            Column(
                Modifier
                    .width(820.dp)
                    // The TV is 960×540 dp: scrolls to Save rather than running off the bottom.
                    .heightIn(max = 500.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Jc.Sheet)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val r = rule
                val d = defaults
                Text(r?.name?.ifEmpty { null } ?: programName.ifEmpty { "Series recording" }, color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (ruleId == null) "Record series" else "Series options") + " · OK changes a row · Back cancels",
                    color = Jc.Muted,
                    fontSize = 14.sp,
                )
                problem?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
                if (r == null || d == null) {
                    if (problem == null) Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                    Button("Close", modifier = Modifier.focusRequester(first)) { onDone(null) }
                    return@Column
                }
                val channel = r.channelName ?: channelName ?: "this channel"
                EditRow(
                    "Record",
                    when {
                        r.type == 7 -> "Every airing, on any channel."
                        r.type in 3..6 && r.start != null -> "On $channel, " + slot(r.type, r.start) + "."
                        else -> "On $channel."
                    },
                    Types.firstOrNull { it.first == r.type }?.second ?: "Type ${r.type}",
                    Modifier.focusRequester(first),
                ) {
                    rule = r.copy(type = stepFrom(Types.map { it.first }, r.type))
                }
                EditRow("Keep", "How many recordings of this series to keep.", if (r.keep <= 0) "All of them" else "The latest ${r.keep}") {
                    rule = r.copy(keep = stepFrom(KeepChoices, nearest(KeepChoices, r.keep)))
                }
                EditRow("Start early", "Begin recording before the guide's start time.", minutes(r.prePadding, "early", d.prePadding)) {
                    rule = r.copy(prePadding = stepFrom(PreChoices, nearest(PreChoices, r.prePadding)))
                }
                EditRow("End late", "Keep recording after the end, for overruns.", minutes(r.postPadding, "late", d.postPadding)) {
                    rule = r.copy(postPadding = stepFrom(PostChoices, nearest(PostChoices, r.postPadding)))
                }
                // Only when the server has more than its default folder.
                if (d.directories.size > 1) {
                    EditRow("Folder", "Where the recordings go (NextPVR's recording folders).", r.directory ?: d.directories.first()) {
                        rule = r.copy(directory = stepFrom(d.directories, r.directory ?: d.directories.first()))
                    }
                }
                if (r.id != null) {
                    EditRow("Series", "Off: records nothing, but stays set up.", if (r.enabled) "On" else "Off") {
                        rule = r.copy(enabled = !r.enabled)
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(if (saving) "Saving…" else if (r.id == null) "Record series" else "Save", primary = true, leading = if (r.id == null) ({ SeriesDots() }) else null) {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            try {
                                backend.saveSeriesRule(r)
                                onDone(if (r.id == null) "Series recording set up." else "Saved: ${r.name}")
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
