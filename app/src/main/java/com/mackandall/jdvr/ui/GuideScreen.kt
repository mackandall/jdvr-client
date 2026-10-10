package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import androidx.compose.foundation.verticalScroll
import com.mackandall.jdvr.data.toUuid
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Program
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val WINDOW_MINUTES = 120L
private const val STEP_MINUTES = 30L

// Programmes are fetched in blocks reaching well past the window, so moving it never shows empty
// rows. Each fetch is for every channel and takes a moment; with only the window loaded, quick
// presses at the right edge kept cancelling it, the focused programme scrolled out of a row with
// nothing new in it, and focus fell to another channel.
private const val BLOCK_HOURS = 3L
private const val FETCH_HOURS = 9L

private fun blockStart(t: LocalDateTime): LocalDateTime = t.truncatedTo(ChronoUnit.HOURS).let { it.minusHours(it.hour % BLOCK_HOURS) }
private val ChannelColumn = 150.dp
private val RowHeight = 62.dp

/** Marks the guide adds from our plugins: unkept live TV buffers (faint) and live Comskip scans (CS). */
private data class GuideExtras(
    val buffers: Set<String> = emptySet(),
    val scanning: Map<String, Int?> = emptyMap(),
) {
    fun isBuffer(p: Program) = p.id.toString().plainId() in buffers

    fun realRecording(p: Program) = p.isRecording && !isBuffer(p)

    fun scanning(p: Program) = p.id.toString().plainId() in scanning
}

private val LocalGuideExtras = androidx.compose.runtime.compositionLocalOf { GuideExtras() }

/** Each programme cell's own focus handle, by programme id: to put focus back on exactly that cell. */
private val LocalCellFocus = androidx.compose.runtime.staticCompositionLocalOf { mutableMapOf<UUID, FocusRequester>() }

private fun floorToHalfHour(t: LocalDateTime): LocalDateTime = t.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (t.minute >= 30) 30 else 0)

/**
 * The guide: channels down, a 2-hour window of programmes across. Right past the end of a row moves
 * the window 30 minutes later (left moves it back, not before now); select a programme for its
 * panel (watch, record, record series, record this instead, Recording Favourite).
 */
@Composable
fun GuideScreen(
    entry: FocusRequester,
    onPlay: (UUID) -> Unit,
    onPlayRecording: (com.mackandall.jdvr.PlayRecording) -> Unit,
    // The channel the live player is on (null when it's closed).
    watching: UUID? = null,
) {
    val infoOnTop = Jdvr.prefs.guideInfo == com.mackandall.jdvr.data.Prefs.TOP
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var windowStart by remember { mutableStateOf(floorToHalfHour(LocalDateTime.now())) }
    var programs by remember { mutableStateOf<Map<UUID, List<Program>>>(emptyMap()) }
    // The span `programs` covers for every channel (null until the first fetch lands).
    var loaded by remember { mutableStateOf<Pair<LocalDateTime, LocalDateTime>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Program?>(null) }
    var focused by remember { mutableStateOf<Program?>(null) }
    // The programme picked to watch: where focus goes back to when the player closes. Kept apart
    // from `focused`, because as the panel and then the player go away, focus falls on whatever
    // cell is at that spot on screen (rows above), and the guide scrolls to it.
    var returnTo by remember { mutableStateOf<Program?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // After the window moves: which channel and moment should take focus.
    var pendingFocus by remember { mutableStateOf<Pair<UUID, LocalDateTime>?>(null) }
    val symbols = rememberMarkSymbols()
    var extras by remember { mutableStateOf(GuideExtras()) }
    val cellFocus = remember { mutableMapOf<UUID, FocusRequester>() }
    val guideScope = androidx.compose.runtime.rememberCoroutineScope()
    // What the last action did, shown briefly once its panel has closed.
    var guideNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(guideNote) {
        if (guideNote != null) {
            delay(3500)
            guideNote = null
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            extras = GuideExtras(Jdvr.liveTv.bufferPrograms(), Jdvr.liveTv.guideScanning())
            delay(60_000)
        }
    }

    LaunchedEffect(Unit) {
        try {
            channels = Jdvr.liveTv.channels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Loading channels failed")
            error = "Couldn't load channels: ${e.message}"
        }
    }

    val block = blockStart(windowStart)
    LaunchedEffect(channels, block, reload) {
        val list = channels ?: return@LaunchedEffect
        val from = block
        val to = block.plusHours(FETCH_HOURS)
        try {
            val fetched = Jdvr.liveTv.programs(list.map { it.id }, from, to).groupBy { it.channelId }
            // Keep what was loaded outside this block (moving back shows it at once); drop what has ended.
            val keepAfter = floorToHalfHour(LocalDateTime.now()).minusHours(1)
            programs =
                (programs.keys + fetched.keys).associateWith { id ->
                    (fetched[id].orEmpty() + programs[id].orEmpty().filter { !it.end.isAfter(from) || !it.start.isBefore(to) })
                        .filter { it.end.isAfter(keepAfter) }
                        .distinctBy { it.id }
                        .sortedBy { it.start }
                }
            loaded =
                loaded?.takeIf { (f, t) -> !from.isAfter(t) && !to.isBefore(f) }?.let { (f, t) -> minOf(f, from) to maxOf(t, to) }
                    ?: (from to to)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Loading programmes failed")
            error = "Couldn't load the guide: ${e.message}"
        }
    }

    // Keep "now" honest: refresh dots and the window's start every couple of minutes.
    LaunchedEffect(Unit) {
        while (true) {
            delay(120_000)
            val floor = floorToHalfHour(LocalDateTime.now())
            if (windowStart.isBefore(floor)) windowStart = floor
            reload++
        }
    }

    // Closing the panel: focus goes back to the programme it was opened on (else it lands on
    // whatever comes first in the grid, channels away).
    fun closePanel() {
        val id = selected?.id
        selected = null
        if (id != null) {
            guideScope.launch {
                // Once the panel is gone and the grid has redrawn, focus that exact cell.
                repeat(10) {
                    delay(60)
                    val r = cellFocus[id] ?: return@repeat
                    if (runCatching { r.requestFocus() }.isSuccess) return@launch
                }
            }
        }
    }

    @Composable
    fun Actions(p: Program) {
        BackHandler { closePanel() }
        ProgramPanel(
            program = p,
            channel = channels?.firstOrNull { it.id == p.channelId },
            symbols = symbols,
            onWatch = {
                returnTo = p
                selected = null
                onPlay(p.channelId)
            },
            onPlayItem = {
                returnTo = p
                selected = null
                onPlayRecording(it)
            },
            onWatchFromStart = { itemId ->
                returnTo = p
                selected = null
                onPlayRecording(com.mackandall.jdvr.PlayRecording(itemId, startMs = 0L))
            },
            onChanged = { reload++ },
            onClose = { closePanel() },
            // An action that worked: close the panel, say what happened, refresh the guide.
            onActed = { msg ->
                guideNote = msg
                closePanel()
                guideScope.launch {
                    delay(1500) // the server schedules in the background
                    reload++
                }
            },
            modifier = Modifier,
        )
    }

    // Moves the window; false when it can't (already at now), so Left can open the rail instead.
    fun shift(
        minutes: Long,
        channelId: UUID,
    ): Boolean {
        val floor = floorToHalfHour(LocalDateTime.now())
        val next = windowStart.plusMinutes(minutes).let { if (it.isBefore(floor)) floor else it }
        if (next == windowStart) return false
        // Not past what's loaded: wait for the next block rather than show rows with nothing to focus.
        val end = loaded?.second
        if (minutes > 0 && (end == null || next.plusMinutes(WINDOW_MINUTES).isAfter(end))) return true
        windowStart = next
        pendingFocus = channelId to (if (minutes > 0) next.plusMinutes(WINDOW_MINUTES - STEP_MINUTES) else next)
        return true
    }

    // Channels changed in the player (▲▼, CH±, digits, mini guide): Back lands on the one last
    // watched, so its row is scrolled on screen while the player is up, ready to take focus.
    LaunchedEffect(watching) {
        val ch = watching ?: return@LaunchedEffect
        val picked = returnTo ?: return@LaunchedEffect
        if (picked.channelId == ch) return@LaunchedEffect
        val index = channels?.indexOfFirst { it.id == ch }?.takeIf { it >= 0 } ?: return@LaunchedEffect
        val now = LocalDateTime.now()
        returnTo = programs[ch].orEmpty().firstOrNull { it.end.isAfter(now) } ?: return@LaunchedEffect
        listState.scrollToItem((index - 2).coerceAtLeast(0))
    }

    // Background art: the programme the cursor has rested on for the set time (Settings).
    val artSeconds = Jdvr.prefs.guideArtSeconds
    var art by remember { mutableStateOf<Program?>(null) }
    LaunchedEffect(focused?.id, artSeconds) {
        if (artSeconds <= 0) {
            art = null
        } else {
            val p = focused ?: return@LaunchedEffect
            kotlinx.coroutines.delay(artSeconds * 1000L)
            art = p
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalGuideExtras provides extras, LocalCellFocus provides cellFocus) {
    // A key pressed in the guide means focus is where the user put it: the way back in follows it again.
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown) returnTo = null
        false
    }) {
    GuideArt(art)
    Column(Modifier.fillMaxSize()) {
    // "Above the guide" (Settings): the details on a band at the top, the grid at full width.
    if (infoOnTop) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(guidePanel())) {
            focused?.let { TopInfo(it, channels?.firstOrNull { c -> c.id == it.channelId }, symbols) }
                ?: if (channels?.isEmpty() != true) Text("Move through the guide to see a programme here; OK for what you can do with it.", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(22.dp)) else Unit
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(if (infoOnTop) 1f else 0.74f).fillMaxHeight().padding(start = 20.dp, top = if (infoOnTop) 8.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Guide", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(windowStart.day(), color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                error?.let { Text(it, color = Jc.Amber, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                guideNote?.let { Text(it, color = Jc.Focus, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            val list = channels
            if (list == null) {
                Spacer(12)
                if (error == null) Text("Loading the guide…", color = Jc.Muted, fontSize = 16.sp)
                return@Column
            }
            if (list.isEmpty()) {
                Spacer(12)
                Text(NO_LIVE_TV, color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.fillMaxWidth(0.8f))
                return@Column
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridWidth = maxWidth - ChannelColumn - 12.dp
                val perMinute = gridWidth / WINDOW_MINUTES.toFloat()
                Column {
                    TimeRuler(windowStart, perMinute)
                    Box {
                        // Behind the cells: shows in the gaps between them, never over the titles.
                        NowLine(windowStart, perMinute)
                        // The way back in (after the rail or a player): the programme picked to watch, else the last one on, else the top-left.
                        val home = (returnTo ?: focused)?.takeIf { p -> list.any { it.id == p.channelId } }
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(list, key = { it.id }) { ch ->
                                GuideRow(
                                    channel = ch,
                                    programs = programs[ch.id].orEmpty(),
                                    windowStart = windowStart,
                                    perMinute = perMinute,
                                    gridWidth = gridWidth,
                                    symbols = symbols,
                                    pendingFocus = pendingFocus?.takeIf { it.first == ch.id }?.second,
                                    onFocusTaken = { pendingFocus = null },
                                    onSelect = { selected = it },
                                    onFocused = { focused = it },
                                    entry = if (ch.id == (home?.channelId ?: list.first().id)) entry else null,
                                    entryProgram = home?.id,
                                    onShift = { minutes -> shift(minutes, ch.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Side layout: the right quarter shows the programme the cursor is on, or its actions after OK.
        if (!infoOnTop) {
            Box(Modifier.weight(0.26f).fillMaxHeight().background(guidePanel(0.6f))) {
                val p = selected
                if (p == null) {
                    focused?.let { InfoPanel(it, channels?.firstOrNull { c -> c.id == it.channelId }, symbols) }
                        ?: if (channels?.isEmpty() != true) Text("Move through the guide to see a programme here; OK for what you can do with it.", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(22.dp)) else Unit
                } else {
                    Actions(p)
                }
            }
        }
    }
    }
    // Top layout: the actions open as a panel over the right of the grid.
    if (infoOnTop) {
        selected?.let { p ->
            Box(Modifier.align(Alignment.CenterEnd).width(380.dp).fillMaxHeight().background(Jc.Sheet)) { Actions(p) }
        }
    }
    }
    }
}

/** The guide's panels: see-through when background art is on (Settings), so the picture shows. */
@Composable
private fun guidePanel(alpha: Float = 0.78f): Color = if (Jdvr.prefs.guideArtSeconds > 0) Jc.Panel.seeThrough(alpha) else Jc.Panel

/**
 * Behind the guide: the rested-on programme's picture, faded in, darkened where the grid is.
 * A landscape still fills the screen; a portrait poster stands full height on the right.
 */
@Composable
private fun GuideArt(program: Program?) {
    androidx.compose.animation.Crossfade(targetState = program?.id, animationSpec = androidx.compose.animation.core.tween(700), label = "guide art") { id ->
        if (id == null) return@Crossfade
        var portrait by remember(id) { mutableStateOf<Boolean?>(null) }
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Jdvr.liveTv.imageUrl(id, 1080),
                contentDescription = null,
                contentScale = if (portrait == true) androidx.compose.ui.layout.ContentScale.FillHeight else androidx.compose.ui.layout.ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                onSuccess = { portrait = it.result.image.height > it.result.image.width },
                modifier = Modifier.fillMaxSize().alpha(if (portrait == null) 0f else 1f),
            )
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to Jc.Ink.copy(alpha = 0.9f),
                        0.6f to Jc.Ink.copy(alpha = 0.78f),
                        1f to Jc.Ink.copy(alpha = 0.45f),
                    ),
                ),
            )
        }
    }
}

/** The details band above the guide ("Above the guide" in Settings). */
/** Recording / buffer / live-scan chips for the programme in the info area. */
@Composable
private fun GuideChips(p: Program) {
    val x = LocalGuideExtras.current
    if (x.realRecording(p)) Chip(if (p.seriesTimerId != null) "● series recording" else "● recording", Jc.Recording)
    if (x.isBuffer(p)) Chip("live TV buffer", Jc.Muted)
    if (x.scanning(p)) Chip("CS · finding commercials" + (x.scanning[p.id.toString().plainId()]?.let { " · ${it}m" } ?: ""), Jc.Amber)
}

@Composable
private fun TopInfo(
    p: Program,
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
) {
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val episode = listOfNotNull(p.seasonEpisode, marks.title).joinToString(" · ")
            if (episode.isNotEmpty()) Text(episode, color = Jc.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${p.start.day()} · ${p.start.time()} – ${p.end.time()}" + (channel?.let { " · ${it.number} ${it.name}" } ?: ""),
                color = Jc.Muted,
                fontSize = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GuideChips(p)
                if (p.isRepeat) Chip("repeat", Jc.Muted)
                if (p.isMovie) Chip("movie", Jc.Muted)
                MarkChips(marks)
            }
        }
        Text(p.overview ?: "", color = Jc.Muted, fontSize = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
    }
}

/** The programme under the cursor: what it is, when, and what's planned for it. */
@Composable
private fun InfoPanel(
    p: Program,
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
) {
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(p.name, color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
        val episode = listOfNotNull(p.seasonEpisode, marks.title).joinToString(" · ")
        if (episode.isNotEmpty()) Text(episode, color = Jc.Text, fontSize = 17.sp)
        Text("${p.start.day()} · ${p.start.time()} – ${p.end.time()}", color = Jc.Muted, fontSize = 15.sp)
        channel?.let { Text("${it.number} ${it.name}", color = Jc.Muted, fontSize = 15.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GuideChips(p)
            if (p.isRepeat) Chip("repeat", Jc.Muted)
            if (p.isMovie) Chip("movie", Jc.Muted)
        }
        MarkChips(marks)
        p.overview?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, maxLines = 10, overflow = TextOverflow.Ellipsis) }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text("OK: watch, record, favourites", color = Jc.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TimeRuler(
    windowStart: LocalDateTime,
    perMinute: Dp,
) {
    Box(Modifier.fillMaxWidth().height(30.dp)) {
        var t = windowStart
        while (t.isBefore(windowStart.plusMinutes(WINDOW_MINUTES))) {
            val x = ChannelColumn + perMinute * Duration.between(windowStart, t).toMinutes().toFloat()
            Text(t.time(), color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.offset(x = x + 6.dp))
            t = t.plusMinutes(STEP_MINUTES)
        }
    }
}

@Composable
private fun NowLine(
    windowStart: LocalDateTime,
    perMinute: Dp,
) {
    val minutes = Duration.between(windowStart, LocalDateTime.now()).toMinutes()
    if (minutes < 0 || minutes > WINDOW_MINUTES) return
    Box(
        Modifier
            .offset(x = ChannelColumn + perMinute * minutes.toFloat())
            .width(2.dp)
            .fillMaxHeight()
            .background(Jc.Focus.copy(alpha = 0.7f)),
    )
}

@Composable
private fun GuideRow(
    channel: Channel,
    programs: List<Program>,
    windowStart: LocalDateTime,
    perMinute: Dp,
    gridWidth: Dp,
    symbols: LiveTvRepository.MarkSymbols?,
    pendingFocus: LocalDateTime?,
    onFocusTaken: () -> Unit,
    onSelect: (Program) -> Unit,
    onFocused: (Program) -> Unit,
    entry: FocusRequester?,
    entryProgram: UUID? = null,
    onShift: (Long) -> Boolean,
) {
    val windowEnd = windowStart.plusMinutes(WINDOW_MINUTES)
    val visible = programs.filter { it.end.isAfter(windowStart) && it.start.isBefore(windowEnd) }.sortedBy { it.start }
    Row(Modifier.height(RowHeight).fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(channel.number, color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = channel.name, modifier = Modifier.height(34.dp).width(84.dp))
            } else {
                Text(channel.name, color = Jc.Muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.width(90.dp))
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.width(gridWidth).fillMaxHeight().background(Jc.Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.CenterStart) {
                Text("No guide data", color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
            }
            return@Row
        }
        visible.forEachIndexed { i, p ->
            val from = if (p.start.isBefore(windowStart)) windowStart else p.start
            val to = if (p.end.isAfter(windowEnd)) windowEnd else p.end
            val width = perMinute * Duration.between(from, to).toMinutes().toFloat()
            // A gap before the first programme (missing guide data).
            if (i == 0 && from.isAfter(windowStart)) {
                androidx.compose.foundation.layout.Spacer(Modifier.width(perMinute * Duration.between(windowStart, from).toMinutes().toFloat()))
            }
            val wantFocus = pendingFocus != null && !p.start.isAfter(pendingFocus) && p.end.isAfter(pendingFocus)
            // Keyed by programme: without it, moving the window reused the focused cell for another
            // programme, so focus never moved and the info panel kept showing the old one.
            androidx.compose.runtime.key(p.id) {
            ProgramCell(
                program = p,
                width = width,
                symbols = symbols,
                requestFocus = wantFocus,
                onFocusTaken = onFocusTaken,
                onClick = { onSelect(p) },
                onFocused = { onFocused(p) },
                entry = if (p.id == entryProgram || (i == 0 && visible.none { it.id == entryProgram })) entry else null,
                onEdgeRight = if (i == visible.lastIndex) ({ onShift(STEP_MINUTES) }) else null,
                onEdgeLeft = if (i == 0) ({ onShift(-STEP_MINUTES) }) else null,
            )
            }
        }
    }
}

@Composable
private fun ProgramCell(
    program: Program,
    width: Dp,
    symbols: LiveTvRepository.MarkSymbols?,
    requestFocus: Boolean,
    onFocusTaken: () -> Unit,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    entry: FocusRequester?,
    onEdgeRight: (() -> Boolean)?,
    onEdgeLeft: (() -> Boolean)?,
) {
    // Its own handle (stable), registered so the guide can refocus it; plus the screen's entry when
    // it's the way back in.
    val focus = remember(program.id) { FocusRequester() }
    val registry = LocalCellFocus.current
    androidx.compose.runtime.DisposableEffect(program.id) {
        registry[program.id] = focus
        onDispose { if (registry[program.id] === focus) registry.remove(program.id) }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focus.requestFocus() }
            onFocusTaken()
        }
    }
    val marks = Marks.parse(program.rawEpisodeTitle, symbols)
    val extras = LocalGuideExtras.current
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .width((width - 4.dp).coerceAtLeast(8.dp))
                .padding(end = 4.dp)
                .fillMaxHeight()
                .focusRequester(focus)
                .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
                .onFocusChangedCompat { if (it) onFocused() }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.DirectionRight && onEdgeRight != null -> onEdgeRight()
                        // At now already: not used, so it reaches the screen and opens the rail.
                        e.key == Key.DirectionLeft && onEdgeLeft != null -> onEdgeLeft()
                        else -> false
                    }
                },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (extras.realRecording(program)) Jc.Recording.copy(alpha = 0.14f) else guidePanel(),
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Red for a recording; faint for a live TV buffer (only kept if someone presses Record).
                if (program.isRecording) Box(Modifier.size(8.dp).background(Jc.Recording.copy(alpha = if (extras.isBuffer(program)) 0.35f else 1f), CircleShape))
                Text(program.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (extras.scanning(program)) Text("CS", color = Jc.OnAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Jc.Amber, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
            }
            val sub = marks.title ?: if (program.isMovie) "Movie" else null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (marks.skipped) Text("■", color = Jc.Amber, fontSize = 12.sp)
                if (marks.otherAiring) Text("↪", color = Jc.Focus, fontSize = 12.sp)
                if (marks.inLibrary) Text("●", color = Jc.Green, fontSize = 12.sp)
                if (marks.watched) Text("✓", color = Jc.Green, fontSize = 12.sp)
                if (sub != null) Text(sub, color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The selected programme: details and what can be done with it. */
@Composable
internal fun ProgramPanel(
    program: Program,
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
    onWatch: () -> Unit,
    onWatchFromStart: (UUID) -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayItem: (com.mackandall.jdvr.PlayRecording) -> Unit = {},
    // When given, a successful action hands its message here (the caller closes the panel);
    // otherwise the panel shows it and stays open.
    onActed: ((String?) -> Unit)? = null,
) {
    var p by remember(program.id) { mutableStateOf(program) }
    // Episode History (watched mark, a copy to play, forget) and JellyDVR Live (is this only a buffer).
    var eh by remember(program.id) { mutableStateOf<LiveTvRepository.EhProgram?>(null) }
    var buffer by remember(program.id) { mutableStateOf<LiveTvRepository.Buffer?>(null) }
    var isBuffer by remember(program.id) { mutableStateOf(false) }
    var armed by remember(program.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(4000)
            armed = null
        }
    }
    // Being recorded now: the recording, to watch from the start (with commercial skipping).
    var recordingItem by remember(program.id) { mutableStateOf<UUID?>(null) }
    LaunchedEffect(program.id) {
        if (program.isRecording && program.isOn()) recordingItem = Jdvr.liveTv.recordingOfProgram(program.id)
    }
    var schedule by remember(program.id) { mutableStateOf<LiveTvRepository.ScheduleState?>(null) }
    var note by remember(program.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)

    suspend fun refresh() {
        Jdvr.liveTv.program(p.id)?.let { p = it }
        schedule = Jdvr.liveTv.scheduleState(p.id)
        eh = Jdvr.liveTv.ehProgram(p.id)
        isBuffer = p.id.toString().plainId() in Jdvr.liveTv.bufferPrograms()
        buffer = Jdvr.liveTv.buffers(p.channelId).lastOrNull { it.programId?.plainId() == p.id.toString().plainId() }
    }
    LaunchedEffect(program.id) {
        eh = Jdvr.liveTv.ehProgram(program.id)
        isBuffer = program.id.toString().plainId() in Jdvr.liveTv.bufferPrograms()
        if (isBuffer) buffer = Jdvr.liveTv.buffers(program.channelId).lastOrNull { it.programId?.plainId() == program.id.toString().plainId() }
        schedule = Jdvr.liveTv.scheduleState(program.id)
        runCatching { first.requestFocus() }
    }

    fun act(block: suspend () -> String?) {
        scope.launch {
            val msg =
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = "Didn't work: ${e.message}"
                    return@launch
                }
            val done = onActed
            if (done != null) {
                done(msg)
                return@launch
            }
            note = msg
            delay(1500) // the server schedules in the background
            refresh()
            onChanged()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .background(Jc.Sheet)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.name, color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        val episode = listOfNotNull(p.seasonEpisode, marks.title).joinToString(" · ")
        if (episode.isNotEmpty()) Text(episode, color = Jc.Text, fontSize = 16.sp)
        Text(
            "${p.start.day()} · ${p.start.time()} – ${p.end.time()}" + (channel?.let { " · ${it.number} ${it.name}" } ?: ""),
            color = Jc.Muted,
            fontSize = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MarkChips(marks)
            if (isBuffer) Chip("Live TV buffer", Jc.Muted)
            if (eh?.markedWatched == true) Chip("marked watched", Jc.Green)
        }
        p.overview?.let { Text(it, color = Jc.Muted, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        schedule?.outcome?.let {
            Text(
                it.detail,
                color = Jc.Amber,
                fontSize = 14.sp,
                modifier = Modifier.background(Jc.Amber.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        Spacer(4)
        if (p.isOn()) Button("Watch live", modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = onWatch)
        recordingItem?.let { id -> Button("Watch from start", modifier = Modifier.fillMaxWidth(), leading = { Text("⏮", color = Jc.Text) }) { onWatchFromStart(id) } }
        // A copy Episode History knows about (recorded before, or in a library).
        eh?.libraryItemId?.toUuid()?.let { lib ->
            val resume = eh?.libraryItemPositionTicks?.div(10_000L) ?: 0L
            Button(if (resume > 60_000) "Play from library · ${resume / 60_000} min in" else "Play from library", modifier = Modifier.fillMaxWidth(), leading = { Text("▶", color = Jc.Text) }) {
                onPlayItem(com.mackandall.jdvr.PlayRecording(lib))
            }
        }
        val recordModifier = if (p.isOn()) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().focusRequester(first)
        val bufferNow = buffer
        if (isBuffer && bufferNow != null && !bufferNow.kept) {
            // Only a buffer so far: Record keeps it (recorded from when it started).
            Button("Record (keep the live buffer)", modifier = recordModifier, leading = { Dot(Jc.Recording) }) {
                act {
                    Jdvr.liveTv.keepBuffer(bufferNow.id, true)
                    "Recording: the live TV buffer is kept."
                }
            }
        } else if (p.isRecording && !isBuffer) {
            Button("Don't record this", modifier = recordModifier, leading = { Dot(Jc.Recording) }) {
                act {
                    Jdvr.liveTv.cancelRecording(p.timerId!!)
                    "Recording cancelled."
                }
            }
        } else {
            Button("Record", modifier = recordModifier, leading = { Dot(Jc.Recording) }) {
                act {
                    Jdvr.liveTv.record(p.id, series = false)
                    "Recording scheduled."
                }
            }
        }
        if (p.isSeries) {
            if (p.seriesTimerId != null) {
                Button("Cancel series recording", modifier = Modifier.fillMaxWidth()) {
                    act {
                        Jdvr.liveTv.cancelSeries(p.seriesTimerId!!)
                        "Series recording cancelled."
                    }
                }
            } else {
                Button("Record series", modifier = Modifier.fillMaxWidth()) {
                    act {
                        Jdvr.liveTv.record(p.id, series = true)
                        "Series recording set up."
                    }
                }
            }
        }
        if (schedule?.outcome != null) {
            Button("Record this instead", modifier = Modifier.fillMaxWidth(), leading = { Dot(Jc.Amber) }) {
                act { Jdvr.liveTv.recordInstead(p.id)?.message }
            }
        }
        if (schedule != null) {
            val fav = schedule?.favourite
            Button(
                if (fav == null) "Add to Recording Favourites" else "Remove from Recording Favourites",
                modifier = Modifier.fillMaxWidth(),
                leading = { Text(if (fav == null) "☆" else "★", color = Jc.Gold, fontSize = 18.sp) },
            ) {
                if (fav != null && armed != "fav") {
                    armed = "fav"
                } else {
                    armed = null
                    act { (if (fav == null) Jdvr.liveTv.addFavourite(p.id) else Jdvr.liveTv.removeFavourite(fav.id))?.message }
                }
            }
            if (armed == "fav") Text("Press again to remove it from Recording Favourites", color = Jc.Amber, fontSize = 13.sp)
        }
        eh?.let { info ->
            Button(if (info.markedWatched) "Unmark watched" else "Mark watched", modifier = Modifier.fillMaxWidth(), leading = { Text("✓", color = Jc.Green) }) {
                act {
                    Jdvr.liveTv.setProgramWatched(p.id, !info.markedWatched)
                    if (info.markedWatched) "No longer marked watched." else "Marked watched: every airing of this episode shows it."
                }
            }
            if (info.canForget) {
                Button(if (armed == "forget") "Press again to forget" else "Forget this episode", modifier = Modifier.fillMaxWidth()) {
                    if (armed != "forget") {
                        armed = "forget"
                    } else {
                        armed = null
                        act {
                            Jdvr.liveTv.forgetProgram(p.id)
                            "Forgotten: it can be recorded again."
                        }
                    }
                }
            }
        }
        note?.let { Text(it, color = Jc.Muted, fontSize = 14.sp) }
    }
}

@Composable
fun Dot(color: androidx.compose.ui.graphics.Color) = Box(Modifier.size(12.dp).background(color, CircleShape))
