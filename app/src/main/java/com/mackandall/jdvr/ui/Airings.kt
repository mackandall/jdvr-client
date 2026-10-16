package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val Reasons = listOf("Partial", "Picture trouble", "Audio trouble", "Ads or edited version", "Other")

/** "Today", "Tomorrow", or the day. */
private fun dayWord(t: LocalDateTime): String =
    when (t.toLocalDate()) {
        LocalDate.now() -> "Today"
        LocalDate.now().plusDays(1) -> "Tomorrow"
        else -> t.day()
    }

/**
 * The line under the title: when it's on next and how many airings, or, when it isn't in the
 * guide, what the guide has of the show and how far it reaches.
 */
private fun summary(
    r: LiveTvRepository.AiringsResult,
    airings: List<LiveTvRepository.Airing>,
    rerecord: Boolean,
    fromGuide: Boolean,
): String {
    if (airings.isNotEmpty()) {
        val next = airings.first()
        val at = localOf(next.start)
        val channel = listOfNotNull(next.channelNumber, next.channelName).joinToString(" ").ifEmpty { null }?.let { " on $it" } ?: ""
        // "today", "tomorrow" mid-sentence; a day name stays as it is.
        val whenText = at?.let { t -> dayWord(t).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it } + " at " + t.time() }
        val first = when {
            next.onNow -> "On now$channel" + if (airings.size > 1) ", and ${airings.size - 1} more in the guide" else ""
            whenText == null -> "${airings.size} airing${if (airings.size == 1) "" else "s"} in the guide"
            airings.size == 1 -> "Airs again $whenText$channel"
            else -> "${airings.size} airings in the guide, the next $whenText$channel"
        }
        return listOfNotNull(first, if (r.tuners > 0) "${r.tuners} tuners" else null).joinToString(" · ")
    }
    val until = localOf(r.guideUntil)?.day()
    val head = when {
        rerecord -> "Not in the guide yet: Automatic goes on looking."
        fromGuide -> "No other airings in the guide."
        else -> "Not in the guide."
    }
    val f = r.guideFirst?.label
    val l = r.guideLast?.label
    val has = when {
        r.isMovie || r.guideShowings == 0 -> null
        f != null && l != null && f != l -> "$f to $l"
        else -> l
    }
    val reach = when {
        has != null -> "The guide has $has" + (until?.let { ", up to $it" } ?: "") + "."
        until != null -> "The guide runs to $until."
        else -> null
    }
    return listOfNotNull(head, reach).joinToString(" ")
}

/** A server time ("…Z", or without a zone: UTC) in local time. */
private fun localOf(iso: String?): LocalDateTime? =
    iso?.let {
        runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(it).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
    }

/**
 * "Other airings" of a movie or episode ([itemId], or the guide entry [programId]): each airing
 * still to come, its channel, whether that channel is commercial-free, a recording already set
 * for it, and whether the tuners are taken then. With [rerecord] it is "Re-record": what's wrong
 * with it, Automatic (Episode History picks the airing, and goes on looking if none is listed),
 * or Re-record here per airing; the new recording replaces the old once it is in full.
 * Otherwise each airing has Record.
 */
@Composable
fun AiringsDialog(
    itemId: UUID? = null,
    programId: UUID? = null,
    rerecord: Boolean = false,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<LiveTvRepository.AiringsResult?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canRecord = deviceRights().record
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(itemId, programId) {
        result = runCatching { Jdvr.liveTv.airings(itemId, programId) }.getOrNull()
        loaded = true
    }
    LaunchedEffect(loaded) {
        repeat(20) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    fun doRerecord(pid: String?) {
        val id = itemId ?: return
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching { Jdvr.liveTv.rerecord(id, pid, reason) }.getOrNull()
            onNote(r?.message?.ifEmpty { null } ?: "It couldn't be set to re-record.")
            busy = false
            onDismiss()
        }
    }

    fun doRecord(a: LiveTvRepository.Airing) {
        val pid = a.programId.toUuid() ?: return
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { Jdvr.liveTv.record(pid, series = false) }.isSuccess
            onNote(if (ok) "Recording ${a.name} ${localOf(a.start)?.let { "on " + it.day() + " at " + it.time() } ?: ""}" else "It couldn't be scheduled.")
            busy = false
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Opened from Quick actions, whose buttons fill the menu: not in here.
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides false) {
        Column(
            Modifier
                .width(700.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Jc.Sheet)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val r = result
            val airings = r?.airings.orEmpty().filter { !it.isThis }
            // Close at the top, where focus starts: one stray OK mustn't record or re-record anything.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (rerecord) "Re-record" else "Other airings", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Button("Close", modifier = Modifier.focusRequester(first), onClick = onDismiss)
            }
            r?.title?.let { Text(it, color = Jc.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                when {
                    !loaded -> "Looking in the guide…"
                    r == null -> "Episode History isn't on this server."
                    else -> summary(r, airings, rerecord, fromGuide = programId != null)
                },
                color = Jc.Muted,
                fontSize = 15.sp,
            )
            if (rerecord && r != null) {
                Text("What's wrong with it?", color = Jc.Muted, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reasons.forEach { x -> Button(x, primary = reason == x) { reason = if (reason == x) null else x } }
                }
                Button(
                    "Automatic: the best airing (a free tuner, commercial-free, soonest)",
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { doRerecord(null) }
            }
            airings.forEach { a ->
                AiringRow(
                    a,
                    action =
                        when {
                            a.onNow || a.recording != null || !canRecord -> null
                            rerecord -> "Re-record here"
                            else -> "Record"
                        },
                ) { if (rerecord) doRerecord(a.programId) else doRecord(a) }
            }
        }
        }
    }
}

@Composable
private fun AiringRow(
    a: LiveTvRepository.Airing,
    action: String?,
    onAction: () -> Unit,
) {
    val at = localOf(a.start)
    Row(
        Modifier.fillMaxWidth().background(Jc.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChannelTile(
            a.channelId?.let { LiveTvRepository.DateChannel(id = it, name = a.channelName.orEmpty(), number = a.channelNumber, hasLogo = a.hasLogo) },
            numberInside = true,
            width = 96,
            height = 56,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(at?.let { dayWord(it) + " · " + it.time() }, if (a.onNow) "on now" else null).joinToString(" · "),
                color = Jc.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(a.channelName?.ifEmpty { null }, a.episodeTitle).joinToString(" · "),
                color = Jc.Muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (a.commercialFree) Chip("commercial-free", Jc.Green)
                if (a.recording != null) Chip(if (a.recording == "series") "● series recording" else "● recording", Jc.Recording)
                when {
                    a.clash -> Chip("all ${a.tuners} tuners busy" + (if (a.clashes.isNotEmpty()) ": " + a.clashes.joinToString(", ") else ""), Jc.Amber)
                    a.busy > 0 -> Chip("${a.busy}${if (a.tuners > 0) " of ${a.tuners}" else ""} tuners busy", Jc.Muted)
                }
            }
        }
        if (action != null) Button(action, onClick = onAction)
    }
}

/**
 * On a recording's actions: "Re-record…" (a recording Jellyfin made, for users allowed to
 * record), "Cancel re-record" once asked (second press), and "Other airings…". Nothing without
 * Episode History.
 */
@Composable
fun RerecordButtons(
    itemId: UUID,
    onNote: (String) -> Unit,
) {
    var state by remember(itemId) { mutableStateOf<LiveTvRepository.RerecordState?>(null) }
    var check by remember(itemId) { mutableIntStateOf(0) }
    var dialog by remember(itemId) { mutableStateOf<Boolean?>(null) }
    var armedCancel by remember(itemId) { mutableStateOf(false) }
    val rights = deviceRights()
    val scope = rememberCoroutineScope()
    LaunchedEffect(itemId, check) { state = runCatching { Jdvr.liveTv.rerecordState(itemId) }.getOrNull() }
    LaunchedEffect(armedCancel) {
        if (armedCancel) {
            delay(4000)
            armedCancel = false
        }
    }
    val s = state ?: return
    if (s.pending) {
        s.text?.let { Text(it, color = Jc.Amber, fontSize = 14.sp) }
        if (s.canManage && rights.record) {
            Button(if (armedCancel) "Press again: cancel the re-record" else "Cancel re-record" + (s.short?.let { " ($it)" } ?: "")) {
                if (!armedCancel) {
                    armedCancel = true
                } else {
                    armedCancel = false
                    scope.launch {
                        onNote(if (Jdvr.liveTv.cancelRerecord(itemId)) "Re-record called off; this recording is kept as it is." else "Couldn't call it off.")
                        check++
                    }
                }
            }
        }
    } else if (s.eligible && s.canManage && rights.record) {
        Button("Re-record…") { dialog = true }
    }
    Button("Other airings…") { dialog = false }
    dialog?.let { rr ->
        AiringsDialog(itemId = itemId, rerecord = rr, onNote = onNote, onDismiss = {
            dialog = null
            check++
        })
    }
}
