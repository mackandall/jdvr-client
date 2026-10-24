package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.mackandall.jdvr.data.KeywordMatch
import com.mackandall.jdvr.data.NextPvrBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private val Matches =
    listOf(
        KeywordMatch.TITLE to "In the title",
        KeywordMatch.ANYWHERE to "In the title or description",
        KeywordMatch.PEOPLE to "In the cast or crew",
    )

// Once, or NextPVR's timeslot types.
private val Repeats =
    listOf(
        0 to "Once",
        3 to "Every day",
        4 to "Every week",
        5 to "Monday to Friday",
        6 to "Weekends",
    )

/** The next (or, [by] -1, previous) item of [list] after [current], round and round. */
private fun <T> stepIn(
    list: List<T>,
    current: T,
    by: Int,
): T = list[(list.indexOf(current) + by).mod(list.size)]

private fun length(minutes: Int): String =
    when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

private fun dayName(d: LocalDate): String =
    when (d) {
        LocalDate.now() -> "Today"
        LocalDate.now().plusDays(1) -> "Tomorrow"
        else -> d.atStartOfDay().day()
    }

/**
 * The frame both rule dialogs share (the series options' look): a title, a hint line, the problem
 * if there is one, then the rows; scrolls when they don't fit the TV.
 */
@Composable
private fun RuleDialog(
    title: String,
    hint: String,
    problem: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                Text(title, color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, color = Jc.Muted, fontSize = 14.sp)
                problem?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
                content()
            }
        }
    }
}

// Focus on the first row once it's there; the dialog's window needs a frame or two first.
@Composable
private fun FocusFirst(
    first: FocusRequester,
    vararg keys: Any?,
) {
    LaunchedEffect(*keys) {
        repeat(20) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** Keep, padding and folder: the rows every NextPVR rule has. */
@Composable
private fun PaddingRows(
    defaults: NextPvrBackend.SeriesDefaults,
    keep: Int?,
    pre: Int,
    post: Int,
    directory: String?,
    onKeep: (Int) -> Unit,
    onPre: (Int) -> Unit,
    onPost: (Int) -> Unit,
    onDirectory: (String) -> Unit,
) {
    if (keep != null) {
        EditRow("Keep", "How many of its recordings to keep.", if (keep <= 0) "All of them" else "The latest $keep") {
            onKeep(stepFrom(KeepChoices, nearest(KeepChoices, keep)))
        }
    }
    EditRow("Start early", "Begin recording before the start time.", minutes(pre, "early", defaults.prePadding)) {
        onPre(stepFrom(PreChoices, nearest(PreChoices, pre)))
    }
    EditRow("End late", "Keep recording after the end, for overruns.", minutes(post, "late", defaults.postPadding)) {
        onPost(stepFrom(PostChoices, nearest(PostChoices, post)))
    }
    if (defaults.directories.size > 1) {
        EditRow("Folder", "Where the recordings go (NextPVR's recording folders).", directory ?: defaults.directories.first()) {
            onDirectory(stepFrom(defaults.directories, directory ?: defaults.directories.first()))
        }
    }
}

@Composable
private fun SaveRow(
    label: String,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(if (saving) "Saving…" else label, primary = true) { if (!saving) onSave() }
        Button("Cancel", onClick = onCancel)
    }
}

private fun failure(e: Exception): String {
    android.util.Log.w("JDVR", "rule save failed", e)
    return "Didn't work: ${e.message ?: e.javaClass.simpleName}"
}

/**
 * A NextPVR keyword rule: records every airing whose title (or description, or cast) has the
 * keyword, on any channel or one; new from [keyword] (a guide entry's name, or empty), or
 * [ruleId]'s to edit. [onDone] gets the message to show, or null when cancelled.
 */
@Composable
fun NextPvrKeywordRule(
    onDone: (String?) -> Unit,
    keyword: String = "",
    ruleId: String? = null,
) {
    val backend = Jdvr.nextPvrBackend ?: return
    var defaults by remember { mutableStateOf<NextPvrBackend.SeriesDefaults?>(null) }
    var channels by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var rule by remember { mutableStateOf<NextPvrBackend.KeywordRule?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            val d = backend.seriesDefaults()
            channels = backend.ruleChannels()
            defaults = d
            rule = if (ruleId != null) backend.keywordRule(ruleId) ?: error("That rule isn't on the server any more") else backend.newKeywordRule(keyword, d)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't load the options: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    FocusFirst(first, rule != null, problem)

    val r = rule
    val d = defaults
    RuleDialog(
        title = if (ruleId == null) "Keyword rule" else r?.name?.ifEmpty { null } ?: "Keyword rule",
        hint = "Records every airing that matches, as NextPVR's Keyword recording · OK changes a row · ◀ ▶ too",
        problem = problem,
        onDismiss = { onDone(null) },
    ) {
        if (r == null || d == null) {
            if (problem == null) Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
            Button("Close", modifier = Modifier.focusRequester(first)) { onDone(null) }
            return@RuleDialog
        }
        if (r.custom != null) {
            // Written some other way (NextPVR's Advanced type): kept as it is.
            EditRow("Rule", "An advanced rule made in NextPVR: change it there.", r.custom, Modifier.focusRequester(first)) {}
        } else {
            Box(Modifier.fillMaxWidth(0.8f)) {
                Field("Keyword (OK to type)", r.keyword, onChange = { rule = r.copy(keyword = it) }, modifier = Modifier.focusRequester(first))
            }
            EditRow("Look", "Where the keyword has to be.", Matches.first { it.first == r.match }.second, onStep = { by -> rule = r.copy(match = stepIn(Matches.map { it.first }, r.match, by)) }) {
                rule = r.copy(match = stepIn(Matches.map { it.first }, r.match, 1))
            }
        }
        val ids = listOf(0) + channels.map { it.first }
        EditRow(
            "Channel",
            "Any channel, or only one.",
            if (r.channelId == 0) "Any channel" else channels.firstOrNull { it.first == r.channelId }?.second ?: "Channel ${r.channelId}",
            onStep = { by -> rule = r.copy(channelId = stepIn(ids, r.channelId, by)) },
        ) {
            rule = r.copy(channelId = stepIn(ids, r.channelId, 1))
        }
        Box(Modifier.fillMaxWidth(0.8f)) {
            Field("Name in the Series list (empty: the keyword)", r.name, onChange = { rule = r.copy(name = it) })
        }
        PaddingRows(
            d, r.keep, r.prePadding, r.postPadding, r.directory,
            onKeep = { rule = r.copy(keep = it) },
            onPre = { rule = r.copy(prePadding = it) },
            onPost = { rule = r.copy(postPadding = it) },
            onDirectory = { rule = r.copy(directory = it) },
        )
        if (r.id != null) {
            EditRow("Rule", "Off: records nothing, but stays set up.", if (r.enabled) "On" else "Off") { rule = r.copy(enabled = !r.enabled) }
        }
        SaveRow(if (r.id == null) "Record keyword" else "Save", saving, onCancel = { onDone(null) }, onSave = {
            if (r.custom == null && r.keyword.isBlank()) {
                problem = "Type a keyword first."
                return@SaveRow
            }
            saving = true
            scope.launch {
                try {
                    backend.saveKeywordRule(r)
                    val name = r.name.ifBlank { r.keyword.ifBlank { r.custom.orEmpty() } }
                    onDone(if (r.id == null) "Keyword rule set up: $name. NextPVR records what it matches." else "Saved: $name")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = failure(e)
                    saving = false
                }
            }
        })
    }
}

/**
 * A NextPVR manual recording: a channel and a time, once or every day / week / weekday / weekend.
 * New from a guide entry's channel and times (or the next half hour), or [ruleId]'s repeating
 * one to edit. [onDone] gets the message to show, or null when cancelled.
 */
@Composable
fun NextPvrManualRecording(
    onDone: (String?) -> Unit,
    channelId: UUID? = null,
    start: LocalDateTime? = null,
    end: LocalDateTime? = null,
    ruleId: String? = null,
) {
    val backend = Jdvr.nextPvrBackend ?: return
    var defaults by remember { mutableStateOf<NextPvrBackend.SeriesDefaults?>(null) }
    var channels by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var rule by remember { mutableStateOf<NextPvrBackend.ManualRule?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            val d = backend.seriesDefaults()
            val list = backend.ruleChannels()
            channels = list
            defaults = d
            rule =
                if (ruleId != null) {
                    backend.manualRule(ruleId) ?: error("That recording isn't on the server any more")
                } else {
                    backend.newManualRule(channelId, start, end, d).let { if (it.channelId == 0) it.copy(channelId = list.firstOrNull()?.first ?: 0) else it }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't load the options: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    FocusFirst(first, rule != null, problem)

    val r = rule
    val d = defaults
    RuleDialog(
        title = if (ruleId == null) "Manual recording" else r?.name?.ifEmpty { null } ?: "Manual recording",
        hint = "A channel and a time, whatever the guide says · OK changes a row · ◀ ▶ too",
        problem = problem,
        onDismiss = { onDone(null) },
    ) {
        if (r == null || d == null) {
            if (problem == null) Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
            Button("Close", modifier = Modifier.focusRequester(first)) { onDone(null) }
            return@RuleDialog
        }
        val channelName = channels.firstOrNull { it.first == r.channelId }?.second ?: "Channel ${r.channelId}"
        val ids = channels.map { it.first }.ifEmpty { listOf(r.channelId) }
        EditRow("Channel", "What to record.", channelName, Modifier.focusRequester(first), onStep = { by -> rule = r.copy(channelId = stepIn(ids, r.channelId, by)) }) {
            rule = r.copy(channelId = stepIn(ids, r.channelId, 1))
        }
        // An existing one repeats (a one-off is a scheduled recording, not a rule).
        val repeats = if (r.id == null) Repeats else Repeats.drop(1)
        EditRow("Repeat", "Once, or the same time again.", repeats.firstOrNull { it.first == r.repeat }?.second ?: "Type ${r.repeat}", onStep = { by -> rule = r.copy(repeat = stepIn(repeats.map { it.first }, r.repeat, by)) }) {
            rule = r.copy(repeat = stepIn(repeats.map { it.first }, r.repeat, 1))
        }
        val today = LocalDate.now()
        fun onDay(by: Int) {
            val day = r.start.toLocalDate().plusDays(by.toLong())
            // From today up to two weeks on (the guide's reach); round the ends.
            val d2 = if (day.isBefore(today)) today.plusDays(13) else if (day.isAfter(today.plusDays(13))) today else day
            rule = r.copy(start = d2.atTime(r.start.toLocalTime()))
        }
        EditRow("Day", if (r.repeat == 0) "The day it records." else "The first day it records.", dayName(r.start.toLocalDate()), onStep = ::onDay) { onDay(1) }
        // ◀ ▶ five minutes; OK half an hour. The day stays put.
        fun onTime(by: Int) {
            rule = r.copy(start = r.start.toLocalDate().atTime(r.start.toLocalTime().plusMinutes(by.toLong())))
        }
        EditRow("Start", "◀ ▶ 5 minutes · OK 30 minutes.", r.start.time(), onStep = { onTime(it * 5) }) { onTime(30) }
        fun onLength(by: Int) {
            rule = r.copy(minutes = (r.minutes + by).coerceIn(5, 600))
        }
        EditRow("Length", "Ends at ${r.start.plusMinutes(r.minutes.toLong()).time()} · ◀ ▶ 5 minutes · OK 30.", length(r.minutes), onStep = { onLength(it * 5) }) {
            onLength(if (r.minutes >= 600) 5 - r.minutes else 30)
        }
        Box(Modifier.fillMaxWidth(0.8f)) {
            Field("Name (empty: ${channelName.substringAfter(' ')} ${r.start.time()})", r.name, onChange = { rule = r.copy(name = it) })
        }
        PaddingRows(
            d, r.keep.takeIf { r.repeat != 0 }, r.prePadding, r.postPadding, r.directory,
            onKeep = { rule = r.copy(keep = it) },
            onPre = { rule = r.copy(prePadding = it) },
            onPost = { rule = r.copy(postPadding = it) },
            onDirectory = { rule = r.copy(directory = it) },
        )
        if (r.id != null) {
            EditRow("Recording", "Off: records nothing, but stays set up.", if (r.enabled) "On" else "Off") { rule = r.copy(enabled = !r.enabled) }
        }
        SaveRow(if (r.id == null) "Record" else "Save", saving, onCancel = { onDone(null) }, onSave = {
            if (r.repeat == 0 && !r.start.plusMinutes(r.minutes.toLong()).isAfter(LocalDateTime.now())) {
                problem = "That time has already gone."
                return@SaveRow
            }
            saving = true
            scope.launch {
                try {
                    backend.saveManualRule(r, channelName.substringAfter(' '))
                    val what = "$channelName, ${dayName(r.start.toLocalDate())} ${r.start.time()}"
                    onDone(if (r.id == null) "Manual recording set up: $what." else "Saved: ${r.name.ifBlank { what }}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = failure(e)
                    saving = false
                }
            }
        })
    }
}
