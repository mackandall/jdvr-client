package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

private val Reasons = listOf("Partial", "Picture trouble", "Audio trouble", "Ads or edited version", "Other")

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
            Text(if (rerecord) "Re-record" else "Other airings", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !loaded -> "Looking in the guide…"
                    r == null -> "Episode History isn't on this server."
                    airings.isEmpty() -> listOfNotNull(r.title, when {
                            rerecord -> "Not in the guide yet: Automatic goes on looking."
                            programId != null -> "No other airings in the guide."
                            else -> "Not in the guide."
                        }).joinToString(" · ")
                    else -> listOfNotNull(r.title, "${airings.size} airing${if (airings.size == 1) "" else "s"} in the guide", if (r.tuners > 0) "${r.tuners} tuners" else null).joinToString(" · ")
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
                    modifier = Modifier.fillMaxWidth().focusRequester(first),
                ) { doRerecord(null) }
            }
            airings.forEachIndexed { i, a ->
                AiringRow(
                    a,
                    action =
                        when {
                            a.onNow || a.recording != null || !canRecord -> null
                            rerecord -> "Re-record here"
                            else -> "Record"
                        },
                    modifier = if (!rerecord && i == 0) Modifier.focusRequester(first) else Modifier,
                ) { if (rerecord) doRerecord(a.programId) else doRecord(a) }
            }
            Button("Close", modifier = if (r == null || (!rerecord && airings.isEmpty())) Modifier.focusRequester(first) else Modifier, onClick = onDismiss)
        }
    }
}

@Composable
private fun AiringRow(
    a: LiveTvRepository.Airing,
    action: String?,
    modifier: Modifier,
    onAction: () -> Unit,
) {
    val at = localOf(a.start)
    Row(
        Modifier.fillMaxWidth().background(Jc.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(at?.let { it.day() + " · " + it.time() }, if (a.onNow) "on now" else null).joinToString(" · "),
                color = Jc.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(listOfNotNull(a.channelNumber, a.channelName).joinToString(" ").ifEmpty { null }, a.episodeTitle).joinToString(" · "),
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
        if (action != null) Button(action, modifier = modifier, onClick = onAction)
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
