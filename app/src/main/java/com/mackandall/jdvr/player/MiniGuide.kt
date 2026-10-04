package com.mackandall.jdvr.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Program
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.Marks
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

private const val ROWS = 4
private const val WINDOW_MIN = 90L

private fun floorHalfHour(t: LocalDateTime): LocalDateTime = t.withSecond(0).withNano(0).withMinute(if (t.minute < 30) 0 else 30)

/**
 * The mini guide: a see-through band over the bottom of live TV (the picture keeps playing full
 * size), four channel rows of a 90-minute timeline. ▲▼ channels, ◀▶ programmes (the window moves
 * along), OK watches what's on now or records / cancels a later one, Back closes.
 */
@Composable
fun MiniGuide(
    channels: List<Channel>,
    currentChannelId: UUID,
    symbols: LiveTvRepository.MarkSymbols?,
    onWatch: (UUID) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Guide programmes that are only a live TV buffer: not shown as recordings.
    buffers: Set<String> = emptySet(),
) {
    fun recording(p: Program) = p.isRecording && p.id.toString().replace("-", "").lowercase() !in buffers
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val earliest = remember { floorHalfHour(LocalDateTime.now()) }
    var windowStart by remember { mutableStateOf(earliest) }
    var row by remember { mutableIntStateOf(channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)) }
    var top by remember { mutableIntStateOf((row - 1).coerceIn(0, (channels.size - ROWS).coerceAtLeast(0))) }
    var at by remember { mutableStateOf(LocalDateTime.now()) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Programmes by channel, fetched a slice of channels at a time.
    val programs = remember { mutableStateMapOf<UUID, List<Program>>() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Fetch the visible rows (and a few either side) for the next few hours.
    LaunchedEffect(top, windowStart, reload) {
        val want = channels.subList((top - 4).coerceAtLeast(0), (top + ROWS + 4).coerceAtMost(channels.size)).map { it.id }.filter { reload > 0 || it !in programs }
        if (want.isEmpty()) return@LaunchedEffect
        val to = windowStart.plusHours(4)
        runCatching { Jdvr.liveTv.programs(want, earliest, to) }.getOrNull()?.let { list ->
            val byChannel = list.groupBy { it.channelId }
            want.forEach { programs[it] = byChannel[it].orEmpty() }
        }
    }

    fun rowPrograms(i: Int) = channels.getOrNull(i)?.let { programs[it.id] }.orEmpty()

    fun selected(): Program? {
        val list = rowPrograms(row)
        return list.firstOrNull { !at.isBefore(it.start) && at.isBefore(it.end) } ?: list.firstOrNull { it.end.isAfter(at) }
    }

    // Keep the selected programme's start inside the window.
    fun follow(p: Program?) {
        p ?: return
        val start = if (p.start.isBefore(earliest)) earliest else p.start
        while (!start.isBefore(windowStart.plusMinutes(WINDOW_MIN))) windowStart = windowStart.plusMinutes(30)
        while (start.isBefore(windowStart) && windowStart.isAfter(earliest)) windowStart = windowStart.minusMinutes(30)
    }

    fun move(dr: Int) {
        row = (row + dr).coerceIn(0, channels.size - 1)
        if (row < top) top = row
        if (row >= top + ROWS) top = row - ROWS + 1
    }

    val sel = selected()
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xDC0D1117))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> move(-1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> move(1)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val next = sel?.let { s -> rowPrograms(row).firstOrNull { !it.start.isBefore(s.end) } }
                        if (next != null) {
                            at = next.start
                            follow(next)
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        val prev = sel?.let { s -> rowPrograms(row).lastOrNull { !it.end.isAfter(s.start) && it.end.isAfter(LocalDateTime.now()) } }
                        if (prev != null) {
                            at = if (prev.start.isBefore(LocalDateTime.now())) LocalDateTime.now() else prev.start
                            follow(prev)
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        val p = sel ?: return@onKeyEvent true
                        if (p.start.isBefore(LocalDateTime.now())) {
                            onWatch(p.channelId)
                        } else {
                            scope.launch {
                                note =
                                    runCatching {
                                        val timer = p.timerId
                                        if (timer != null) {
                                            Jdvr.liveTv.cancelRecording(timer)
                                            "Won't record ${p.name}"
                                        } else {
                                            Jdvr.liveTv.record(p.id, series = false)
                                            "Will record ${p.name} at ${p.start.time()}"
                                        }
                                    }.getOrElse { "Didn't work: ${it.message}" }
                                reload++
                                delay(3000)
                                note = null
                            }
                        }
                    }
                    else -> return@onKeyEvent false
                }
                true
            }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // The selected programme.
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val marks = Marks.parse(sel?.rawEpisodeTitle, symbols)
            Text(sel?.name ?: "", color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val ch = channels.getOrNull(row)
            Text(
                listOfNotNull(sel?.seasonEpisode, marks.title, sel?.let { "${it.start.time()}–${it.end.time()}" }, ch?.let { "${it.number} ${it.name}" }).joinToString(" · "),
                color = Jc.Muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (sel != null && recording(sel)) Text("● Recording", color = Jc.Recording, fontSize = 13.sp)
            Text(
                note ?: if (sel != null && sel.start.isBefore(LocalDateTime.now())) "OK watch · Back close" else "OK ${if (sel?.isRecording == true) "don't record" else "record"} · Back close",
                color = if (note != null) Jc.Text else Jc.Muted,
                fontSize = 13.sp,
            )
        }
        // Time ruler.
        Row(Modifier.height(18.dp)) {
            Box(Modifier.width(150.dp))
            Row(Modifier.weight(1f)) {
                repeat(3) { i -> Text(windowStart.plusMinutes(30L * i).time(), color = Jc.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f)) }
            }
        }
        for (i in top until (top + ROWS).coerceAtMost(channels.size)) {
            val ch = channels[i]
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    Modifier.width(145.dp).fillMaxHeight().background(if (i == row) Jc.FocusFill else Jc.Panel.copy(alpha = 0.9f), RoundedCornerShape(7.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(ch.number, color = Jc.Muted, fontSize = 13.sp)
                    Text(ch.name, color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val w = maxWidth
                    val windowEnd = windowStart.plusMinutes(WINDOW_MIN)
                    rowPrograms(i).filter { it.end.isAfter(windowStart) && it.start.isBefore(windowEnd) }.forEach { p ->
                        val from = (Duration.between(windowStart, maxOf(p.start, windowStart)).toMinutes().toFloat() / WINDOW_MIN).coerceIn(0f, 1f)
                        val to = (Duration.between(windowStart, minOf(p.end, windowEnd)).toMinutes().toFloat() / WINDOW_MIN).coerceIn(0f, 1f)
                        val isSel = i == row && p.id == sel?.id
                        Column(
                            Modifier
                                .offset(x = w * from)
                                .width((w * (to - from) - 4.dp).coerceAtLeast(6.dp))
                                .fillMaxHeight()
                                .background(if (isSel) Jc.FocusFill else Jc.Panel2.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                                .then(if (isSel) Modifier.border(2.dp, Jc.Focus, RoundedCornerShape(7.dp)) else Modifier)
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text((if (recording(p)) "● " else "") + p.name, color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Marks.parse(p.rawEpisodeTitle, symbols).title?.let { Text(it, color = Jc.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (programs[ch.id] == null) Text("…", color = Jc.Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                    // Now.
                    val nowFrac = Duration.between(windowStart, LocalDateTime.now()).toMinutes().toFloat() / WINDOW_MIN
                    if (nowFrac in 0f..1f) Box(Modifier.offset(x = w * nowFrac).width(2.dp).fillMaxHeight().background(Jc.Focus.copy(alpha = 0.8f)))
                }
            }
        }
    }
}
