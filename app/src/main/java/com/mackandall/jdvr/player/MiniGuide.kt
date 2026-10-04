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
import androidx.compose.foundation.layout.widthIn
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
 * size), four channel rows of a 90-minute timeline. ▲▼ channels (CH± a page, digits jump to a
 * number), ◀▶ programmes (the window moves along; ⏩ ⏪ two hours), OK watches what's on now or opens
 * a later one's panel (Info / Menu: any programme's), with the guide's options. Back closes.
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
    onPlayRecording: (com.mackandall.jdvr.PlayRecording) -> Unit = {},
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
    // Programmes by channel, fetched a slice of channels at a time, and how far ahead each is.
    val programs = remember { mutableStateMapOf<UUID, List<Program>>() }
    val fetchedTo = remember { mutableMapOf<UUID, LocalDateTime>() }
    // A programme's full panel (the guide's: record, series, favourites, watched…).
    var detail by remember { mutableStateOf<Program?>(null) }
    var typed by remember { mutableStateOf("") }
    // Recent channels (not this one), most recent first; ◀ at "now" goes up to them.
    val recent = remember { Jdvr.session.recentChannels.mapNotNull { id -> channels.firstOrNull { it.id.toString() == id } }.filter { it.id != currentChannelId }.take(6) }
    var inRecent by remember { mutableStateOf(false) }
    var recentIndex by remember { mutableIntStateOf(0) }
    var typedAt by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Fetch the visible rows (and a few either side) for the next few hours.
    LaunchedEffect(top, windowStart, reload) {
        val need = windowStart.plusMinutes(WINDOW_MIN)
        val want =
            channels.subList((top - 4).coerceAtLeast(0), (top + ROWS + 4).coerceAtMost(channels.size)).map { it.id }
                .filter { reload > 0 || fetchedTo[it]?.isBefore(need) != false }
        if (want.isEmpty()) return@LaunchedEffect
        val to = windowStart.plusHours(4)
        runCatching { Jdvr.liveTv.programs(want, earliest, to) }.getOrNull()?.let { list ->
            val byChannel = list.groupBy { it.channelId }
            want.forEach {
                programs[it] = byChannel[it].orEmpty()
                fetchedTo[it] = to
            }
        }
    }

    // Digits typed: after a pause, jump to that channel number.
    LaunchedEffect(typedAt) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(1500)
        val i = channels.indexOfFirst { it.number == typed }
        if (i >= 0) {
            row = i
            top = (i - 1).coerceIn(0, (channels.size - ROWS).coerceAtLeast(0))
        } else {
            note = "No channel $typed"
        }
        typed = ""
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
    if (inRecent) androidx.activity.compose.BackHandler { inRecent = false }
    // The panel closed: the band has the remote again.
    LaunchedEffect(detail) { if (detail == null) runCatching { focus.requestFocus() } }
    Box(modifier) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Jc.Ink.copy(alpha = 0.86f))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (inRecent) {
                    when (e.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> recentIndex = (recentIndex - 1).coerceAtLeast(0)
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> recentIndex = (recentIndex + 1).coerceAtMost(recent.lastIndex)
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> inRecent = false
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> recent.getOrNull(recentIndex)?.let { onWatch(it.id) }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {}
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> move(-1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> move(1)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> move(-ROWS)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> move(ROWS)
                    // Two hours ahead / back (not before now).
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        windowStart = windowStart.plusHours(2)
                        at = windowStart
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        windowStart = windowStart.minusHours(2).let { if (it.isBefore(earliest)) earliest else it }
                        at = if (windowStart == earliest) LocalDateTime.now() else windowStart
                    }
                    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_MENU -> detail = sel
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        typed = (typed + (e.key.nativeKeyCode - AndroidKeyEvent.KEYCODE_0)).takeLast(4)
                        typedAt = System.currentTimeMillis()
                    }
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
                        } else if (recent.isNotEmpty()) {
                            // At "now": up to the recent channels.
                            inRecent = true
                            recentIndex = 0
                        }
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        val p = sel ?: return@onKeyEvent true
                        if (p.start.isBefore(LocalDateTime.now())) onWatch(p.channelId) else detail = p
                    }
                    else -> return@onKeyEvent false
                }
                true
            }.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Recent channels: ◀ at "now" comes up here; OK tunes, ▼ or Back goes back down.
        if (recent.isNotEmpty()) {
            Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recent", color = if (inRecent) Jc.Text else Jc.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                recent.forEachIndexed { i, ch ->
                    val on = inRecent && i == recentIndex
                    Row(
                        Modifier
                            .background(if (on) Jc.FocusFill else Jc.Panel.copy(alpha = 0.9f), RoundedCornerShape(7.dp))
                            .then(if (on) Modifier.border(2.dp, Jc.Focus, RoundedCornerShape(7.dp)) else Modifier)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ch.number, color = Jc.Muted, fontSize = 12.sp)
                        Text(ch.name, color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        ch.now?.let { Text(it.name, color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) }
                    }
                }
            }
        }
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
                note ?: (if (typed.isNotEmpty()) "Channel $typed…" else if (sel != null && sel.start.isBefore(LocalDateTime.now())) "OK watch · ◀ recent · Info options · Back close" else "OK options · ⏩⏪ 2 h · Back close"),
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
    // The guide's programme panel, on the right over the picture.
    detail?.let { p ->
        androidx.activity.compose.BackHandler { detail = null }
        com.mackandall.jdvr.ui.ProgramPanel(
            program = p,
            channel = channels.firstOrNull { it.id == p.channelId },
            symbols = symbols,
            onWatch = {
                detail = null
                onWatch(p.channelId)
            },
            onWatchFromStart = { id ->
                detail = null
                onPlayRecording(com.mackandall.jdvr.PlayRecording(id, startMs = 0L))
            },
            onPlayItem = {
                detail = null
                onPlayRecording(it)
            },
            onChanged = { reload++ },
            onClose = { detail = null },
            onActed = { msg ->
                note = msg
                detail = null
                scope.launch {
                    delay(1500)
                    reload++
                    delay(2000)
                    note = null
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd).width(380.dp),
        )
    }
    }
}
