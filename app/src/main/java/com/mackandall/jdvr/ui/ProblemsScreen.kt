package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.AlertsList
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.ServerAlert
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private fun localAt(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

private fun whenText(iso: String?): String? = localAt(iso)?.let { "${it.day()} ${it.time()}" }

/** The colour of a problem's label: red for a recording that produced nothing, amber for the rest. */
@Composable
private fun labelColour(a: ServerAlert): Color = if (a.severe) Jc.Recording else Jc.Amber

/** "Sat 7:00 PM · KOKO 5.1": when it aired (or was seen) and where. */
private fun whereWhen(a: ServerAlert): String = listOfNotNull(whenText(a.airedUtc ?: a.createdUtc), a.where?.ifBlank { null }).joinToString(" · ")

/**
 * The server's problems: recordings that failed or came out short, a stuck tuner, a full drive,
 * an old guide. Open ones first (OK: what happened, what happens next, and what can be done:
 * play what was recorded, other airings, dismiss), then those cleared this week.
 */
@Composable
fun ProblemsScreen(
    entry: FocusRequester,
    onPlayRecording: (PlayRecording) -> Unit,
) {
    var list by remember { mutableStateOf<AlertsList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var airingsFor by remember { mutableStateOf<ServerAlert?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        attempt { Jdvr.backend.alerts() }?.let {
            list = it
            OpenProblems.note(it)
        }
        loaded = true
        // Seen here: the popup won't bring these up again.
        list?.let { Jdvr.session.problemsSeenUtc = newestOf(it.open) ?: Jdvr.session.problemsSeenUtc }
        // After a dismiss the row that had focus is gone: back to the top of the list.
        if (reload > 0 && dismissed) {
            dismissed = false
            entry.focusSoon()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            reload++
        }
    }

    val l = list
    val openOnes = l?.shownOpen.orEmpty()
    val closed = l?.closed.orEmpty().filter { it.onTv }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Problems", color = Jc.Title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            if (l != null) {
                Text(
                    (if (openOnes.isEmpty()) "Nothing open" else "${openOnes.size} open") +
                        (if (closed.isNotEmpty()) " · ${closed.size} cleared this week" else ""),
                    color = Jc.Muted,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            note?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Spacer(12)
        // After an outage that failed a pile of recordings at once: all of them in one go.
        if (openOnes.size >= 2) {
            val confirmAll = rememberConfirm<Unit>(openOnes.size)
            Button(if (confirmAll.isArmed) "Press again: dismiss all ${openOnes.size}" else "Dismiss all ${openOnes.size}") {
                confirmAll.press {
                    scope.launch {
                        val n = attempt { Jdvr.backend.dismissAllAlerts() }
                        note = if (n == null) "Couldn't dismiss them." else "Dismissed $n."
                        open = null
                        dismissed = true
                        reload++
                    }
                }
            }
            Spacer(10)
        }
        when {
            !loaded -> {
                Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            }

            l == null -> {
                Text("Couldn't load the problems (they need JellyDVR Schedule 1.2 on the server).", color = Jc.Amber, fontSize = 16.sp, modifier = Modifier.focusRequester(entry))
            }

            openOnes.isEmpty() && closed.isEmpty() -> {
                Text("No problems this week: every recording, tuner and the guide are fine.", color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.focusRequester(entry))
            }

            else -> {
                val first = (openOnes + closed).first().id
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(openOnes, key = { it.id }) { a ->
                        ProblemRow(
                            a = a,
                            expanded = open == a.id,
                            modifier = if (a.id == first) Modifier.focusRequester(entry) else Modifier,
                            onToggle = { open = if (open == a.id) null else a.id },
                            onPlay = a.itemId?.toUuid()?.let { id -> { onPlayRecording(PlayRecording(id)) } },
                            onAirings = if (a.programId != null || a.itemId != null) ({ airingsFor = a }) else null,
                            onDismiss = {
                                scope.launch {
                                    val ok = attempt { Jdvr.backend.dismissAlert(a.id) } ?: false
                                    note = if (ok) "Dismissed: ${a.title}" else "Couldn't dismiss it."
                                    open = null
                                    dismissed = true
                                    reload++
                                }
                            },
                        )
                    }
                    if (closed.isNotEmpty()) {
                        item(key = "closedHead") {
                            Text("CLEARED THIS WEEK", color = Jc.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                        }
                        items(closed, key = { it.id }) { a ->
                            ProblemRow(
                                a = a,
                                expanded = open == a.id,
                                modifier = if (a.id == first) Modifier.focusRequester(entry) else Modifier,
                                onToggle = { open = if (open == a.id) null else a.id },
                                onPlay = a.itemId?.toUuid()?.let { id -> { onPlayRecording(PlayRecording(id)) } },
                                onAirings = null,
                                onDismiss = null,
                            )
                        }
                    }
                }
            }
        }
    }
    airingsFor?.let { a ->
        AiringsDialog(
            itemId = if (a.programId == null) a.itemId?.toUuid() else null,
            programId = a.programId?.toUuid(),
            onNote = { note = it },
            onDismiss = { airingsFor = null },
        )
    }
}

@Composable
private fun ProblemRow(
    a: ServerAlert,
    expanded: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onPlay: (() -> Unit)?,
    onAirings: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
) {
    val isOpen = a.state == "open"
    Column {
        Surface(
            onClick = onToggle,
            modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = if (isOpen) Jc.Panel else Jc.Panel.copy(alpha = 0.55f), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isOpen) {
                    Text(a.label, color = labelColour(a), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                } else {
                    Text(if (a.state == "fixed") "✓" else "–", color = if (a.state == "fixed") Jc.Green else Jc.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(a.title, fontSize = 17.sp, fontWeight = if (isOpen) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (isOpen) {
                            listOf(whereWhen(a), a.next ?: a.text).filter { it.isNotBlank() }.joinToString(" · ")
                        } else {
                            a.closedText ?: (if (a.state == "dismissed") "Dismissed" else "Over")
                        },
                        color = Jc.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (a.repeats > 0 && isOpen) Chip("seen ${a.repeats + 1}×", Jc.Muted)
            }
        }
        if (expanded) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.tryFocus() }
            Column(Modifier.padding(start = 92.dp, top = 8.dp, bottom = 10.dp, end = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.text, color = Jc.Text, fontSize = 15.sp)
                a.next?.let { Text(it, color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                // When it aired and where; and when the server saw it, if that's another time.
                Text(
                    listOfNotNull(whereWhen(a).ifBlank { null }, if (a.airedUtc != null) "seen " + (whenText(a.createdUtc) ?: "") else null).joinToString(" · "),
                    color = Jc.Muted,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    var firstUsed = false

                    fun mod(): Modifier =
                        if (!firstUsed) {
                            firstUsed = true
                            Modifier.focusRequester(first)
                        } else {
                            Modifier
                        }
                    onPlay?.let { Button("Play what was recorded", modifier = mod(), onClick = it) }
                    onAirings?.let { Button("Other airings", modifier = mod(), onClick = it) }
                    onDismiss?.let { Button("Dismiss", modifier = mod(), onClick = it) }
                    Button("Close", modifier = mod(), onClick = onToggle)
                }
            }
        }
    }
}

/**
 * What the menu and its edge mark need from the server's problems: how many this TV shows open, and
 * the server's "pop up" switch (for TVs that follow it). Kept fresh by [ProblemsPopup] and the
 * Problems page.
 */
object OpenProblems {
    var count by mutableIntStateOf(0)
    var serverPopup by mutableStateOf(true)

    fun note(l: AlertsList) {
        count = l.shownOpen.size
        serverPopup = l.tv.popupOnOpen
    }

    /** This TV's way of showing them (Settings › Problems). */
    val mode get() = Jdvr.prefs.problemsMode(serverPopup)
}

/** The newest creation time among [alerts] (ISO), for "seen up to". */
fun newestOf(alerts: List<ServerAlert>): String? = alerts.mapNotNull { a -> runCatching { Instant.parse(a.createdUtc) }.getOrNull() }.maxOrNull()?.toString()

private fun newerThan(
    a: ServerAlert,
    seen: String?,
): Boolean {
    val at = runCatching { Instant.parse(a.createdUtc) }.getOrNull() ?: return false
    val s = seen?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
    return at.isAfter(s)
}

/**
 * Asks the server for problems when the app opens (and comes back to the front): new ones since
 * this TV last saw them show in a popup, "See problems" or "Later". While something plays, only
 * a failed recording shows, as a banner that takes no focus (and only if the server's setting
 * allows it). [resumed] changes each time the app comes to the front.
 */
@Composable
fun ProblemsPopup(
    resumed: Int,
    playing: Boolean,
    onSeeProblems: () -> Unit,
) {
    var fresh by remember { mutableStateOf<List<ServerAlert>>(emptyList()) }
    var banner by remember { mutableStateOf<ServerAlert?>(null) }
    var tv by remember { mutableStateOf<com.mackandall.jdvr.data.AlertsTv?>(null) }
    var checked by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumed) {
        // Just after the TV wakes its network may not be up yet: try again for a minute or so.
        var l = attempt { Jdvr.backend.alerts() }
        var tries = 0
        while (l == null && tries < 12) {
            delay(5_000)
            l = attempt { Jdvr.backend.alerts() }
            tries++
        }
        if (l == null) return@LaunchedEffect
        tv = l.tv
        OpenProblems.note(l)
        val seen = Jdvr.session.problemsSeenUtc
        val newer = l.shownOpen.filter { newerThan(it, seen) }
        // This TV's choice (Settings › Problems): a window for all, for failed recordings only, or none.
        fresh =
            when (OpenProblems.mode) {
                Prefs.PROBLEMS_POPUP -> newer
                Prefs.PROBLEMS_QUIET -> if (Jdvr.prefs.problemsPopupFailed) newer.filter { it.severe } else emptyList()
                else -> emptyList()
            }
        checked++
    }
    // The count in the menu and its edge mark: kept up to date while the app is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(180_000)
            attempt { Jdvr.backend.alerts() }?.let { OpenProblems.note(it) }
        }
    }
    // During playback: look every two minutes for a recording that failed.
    val bannerAllowed =
        when (OpenProblems.mode) {
            Prefs.PROBLEMS_POPUP -> true
            Prefs.PROBLEMS_QUIET -> Jdvr.prefs.problemsPopupFailed
            else -> false
        }
    LaunchedEffect(playing, tv?.popupDuringPlayback, bannerAllowed) {
        if (!playing || tv?.popupDuringPlayback != true || !bannerAllowed) return@LaunchedEffect
        var seenWhilePlaying = Jdvr.session.problemsSeenUtc
        while (true) {
            delay(120_000)
            val l = attempt { Jdvr.backend.alerts() } ?: continue
            val failed = l.shownOpen.filter { it.severe && newerThan(it, seenWhilePlaying) }
            if (failed.isNotEmpty()) {
                seenWhilePlaying = newestOf(failed)
                banner = failed.first()
            }
        }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(10_000)
            banner = null
        }
    }

    banner?.let { b ->
        if (playing) {
            Box(Modifier.fillMaxSize().padding(top = 28.dp, end = 28.dp), contentAlignment = Alignment.TopEnd) {
                Row(
                    Modifier.width(520.dp).background(Jc.Sheet, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("FAILED", color = Jc.Recording, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(b.title, color = Jc.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("See Problems after you've finished watching", color = Jc.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (fresh.isEmpty() || playing) return
    val shown = fresh

    fun seen() {
        Jdvr.session.problemsSeenUtc = newestOf(shown) ?: Jdvr.session.problemsSeenUtc
        fresh = emptyList()
    }
    val first = remember(checked) { FocusRequester() }
    LaunchedEffect(checked) { first.focusSoon() }
    Dialog(onDismissRequest = { seen() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(620.dp).background(Jc.Sheet, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).background(Jc.Amber.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Text("!", color = Jc.Amber, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    val recordings = shown.all { it.kind == "failed" || it.kind == "short" }
                    Text(
                        if (shown.size == 1) {
                            if (recordings) "A recording needs a look" else "Something needs a look"
                        } else {
                            "${shown.size} " + (if (recordings) "recordings need" else "problems need") + " a look"
                        },
                        color = Jc.Title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Since this TV last checked", color = Jc.Muted, fontSize = 14.sp)
                }
            }
            shown.take(4).forEach { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(a.label, color = labelColour(a), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = Jc.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.next ?: a.text, color = Jc.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (shown.size > 4) Text("and ${shown.size - 4} more", color = Jc.Muted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button("See problems", primary = true, modifier = Modifier.focusRequester(first)) {
                    seen()
                    onSeeProblems()
                }
                Button("Later") { seen() }
            }
        }
    }
}
