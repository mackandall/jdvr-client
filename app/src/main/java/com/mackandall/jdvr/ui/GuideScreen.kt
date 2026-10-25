package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.Program
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import com.mackandall.jdvr.data.MarkSymbols
import com.mackandall.jdvr.data.attempt

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
    // Programmes a Recording Favourite will record (JellyDVR Schedule): SageTV's green cells.
    val favourites: Set<String> = emptySet(),
) {
    fun isBuffer(p: Program) = p.id.toString().plainId() in buffers

    fun isFavourite(p: Program) = p.id.toString().plainId() in favourites

    fun realRecording(p: Program) = p.isRecording && !isBuffer(p)

    fun scanning(p: Program) = p.id.toString().plainId() in scanning
}

private val LocalGuideExtras = androidx.compose.runtime.compositionLocalOf { GuideExtras() }

/** Each programme cell's own focus handle, by programme id: to put focus back on exactly that cell. */
/** The channel last focused in the guide (this run): where the guide opens next time. */
private var lastGuideChannel: Pair<String, UUID>? = null

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
    // Opened at a later time (SageTV's "Tonight") or on a chosen channel ("Jump to a channel").
    startAt: LocalDateTime? = null,
    startChannel: UUID? = null,
) {
    val infoOnTop = Jdvr.prefs.guideInfo == com.mackandall.jdvr.data.Prefs.TOP
    val infoBelow = Jdvr.prefs.guideInfo == com.mackandall.jdvr.data.Prefs.BOTTOM
    // Above or below: the details on a band, the grid at full width.
    val infoBand = infoOnTop || infoBelow
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var windowStart by remember { mutableStateOf(floorToHalfHour(maxOf(startAt ?: LocalDateTime.now(), LocalDateTime.now()))) }
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
            val favourites =
                if (Jc.Style == UiStyle.Sage) {
                    Jdvr.backend.upcoming().orEmpty().filter { it.kind == "favourite" }.mapNotNull { it.programId?.plainId() }.toSet()
                } else {
                    emptySet()
                }
            extras = GuideExtras(Jdvr.backend.bufferPrograms(), Jdvr.backend.guideScanning(), favourites)
            delay(60_000)
        }
    }

    // Channel groups (NextPVR's; on Jellyfin all channels / favourites): a button by the title picks the one shown, and it's kept.
    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
    val groupFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { groups = attempt { Jdvr.backend.channelGroups() } ?: emptyList() }
    val group = Jdvr.prefs.channelGroup
    LaunchedEffect(group) {
        try {
            channels = Jdvr.backend.channels()
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
            val fetched = Jdvr.backend.programs(list.map { it.id }, from, to).groupBy { it.channelId }
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
                    if (r.tryFocus()) return@launch
                }
            }
        }
    }

    // "Other airings" of a programme, opened from its panel (which closes as the dialog takes focus).
    var airingsFor by remember { mutableStateOf<Program?>(null) }

    // Focus gone from the actions panel (◄ to the menu, or back into the grid): it closes. After a
    // moment, so a button the panel swaps for another (Record → Cancel recording) doesn't close it.
    var panelHasFocus by remember { mutableStateOf(false) }
    var panelHadFocus by remember(selected?.id) { mutableStateOf(false) }
    LaunchedEffect(panelHasFocus, selected?.id) {
        if (panelHasFocus) {
            panelHadFocus = true
        } else if (panelHadFocus && selected != null) {
            delay(200)
            selected = null
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
            onOtherAirings = { airingsFor = it },
            // An action that worked: close the panel, say what happened, refresh the guide.
            onActed = { msg ->
                guideNote = msg
                closePanel()
                guideScope.launch {
                    delay(1500) // the server schedules in the background
                    reload++
                }
            },
            modifier = Modifier.onFocusChanged { panelHasFocus = it.hasFocus },
        )
    }

    // ▲▼ keep to the time picked with ◄► (a guide's "column"): from a long programme, Compose's own
    // focus search aims at the middle of the cell and lands an hour or more ahead, then drifts on.
    var anchor by remember { mutableStateOf<LocalDateTime?>(null) }
    // The cell a ▲▼ is moving to: its focus mustn't reset the anchor.
    var verticalTo by remember { mutableStateOf<UUID?>(null) }
    var gridHasFocus by remember { mutableStateOf(false) }
    fun onCellFocused(p: Program) {
        focused = p
        lastGuideChannel = Jdvr.serverKey to p.channelId
        if (p.id == verticalTo) verticalTo = null else anchor = maxOf(p.start, windowStart)
    }

    // ▲▼: the programme on at the anchor time in the next channel with guide data that way.
    fun moveVertical(dir: Int): Boolean {
        val list = channels ?: return false
        val from = focused ?: return false
        val at = maxOf(anchor ?: from.start, windowStart)
        val windowEnd = windowStart.plusMinutes(WINDOW_MINUTES)
        var t = list.indexOfFirst { it.id == from.channelId }
        if (t < 0) return false
        var target: Program? = null
        while (target == null) {
            t += dir
            // The edge: stay put (▲ off the top goes to the channel group button, when there is one).
            if (t !in list.indices) return true.also { if (t < 0 && groups.size > 1) groupFocus.tryFocus() }
            val row = programs[list[t].id].orEmpty().filter { it.end.isAfter(windowStart) && it.start.isBefore(windowEnd) }.sortedBy { it.start }
            target = row.firstOrNull { !it.start.isAfter(at) && it.end.isAfter(at) } ?: row.firstOrNull { it.start.isAfter(at) } ?: row.lastOrNull()
        }
        val goal = target
        val index = t
        verticalTo = goal.id
        if (cellFocus[goal.id]?.let { it.tryFocus() } == true && focused?.id == goal.id) return true
        // Its row isn't laid out yet (fast scrolling): bring it on screen, then focus it.
        guideScope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.size.coerceAtLeast(1)
            if (visible.none { it.index == index }) listState.scrollToItem(if (dir > 0) (index - shown + 2).coerceAtLeast(0) else index)
            repeat(20) {
                if (cellFocus[goal.id]?.let { it.tryFocus() } == true && focused?.id == goal.id) return@launch
                kotlinx.coroutines.delay(16)
            }
        }
        return true
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

    // Opening: the channel last focused in the guide, else the one last watched; its row scrolled
    // near the top so it's there to take focus.
    val openingOn = remember {
        startChannel ?: lastGuideChannel?.takeIf { it.first == Jdvr.serverKey }?.second ?: runCatching { UUID.fromString(Jdvr.session.lastChannelId) }.getOrNull()
    }
    // Where the opening programme is looked for: now, or the time asked for.
    val openingAt = remember { maxOf(startAt ?: LocalDateTime.now(), LocalDateTime.now()) }
    LaunchedEffect(channels) {
        val list = channels ?: return@LaunchedEffect
        if (focused != null || returnTo != null) return@LaunchedEffect
        val index = list.indexOfFirst { it.id == openingOn }.takeIf { it >= 0 } ?: return@LaunchedEffect
        listState.scrollToItem((index - 2).coerceAtLeast(0))
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
        // ▲▼ in the grid (not in the actions panel over it).
        if (e.type == KeyEventType.KeyDown && gridHasFocus && selected == null) {
            when (e.key) {
                Key.DirectionDown -> return@onPreviewKeyEvent moveVertical(1)
                Key.DirectionUp -> return@onPreviewKeyEvent moveVertical(-1)
            }
        }
        false
    }) {
    GuideArt(art)
    Column(Modifier.fillMaxSize()) {
    // "Above the guide" / "Below the guide" (Settings): the details on a band, the grid at full width.
    @Composable
    fun band() =
        // Light: the picture sits behind the details.
        Box(Modifier.fillMaxWidth().height(BandHeight).background(guidePanel(0.3f))) {
            focused?.let { InfoBand(it, channels?.firstOrNull { c -> c.id == it.channelId }, symbols) }
                ?: if (channels?.isEmpty() != true) Text("Move through the guide to see a programme here; OK for what you can do with it.", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(22.dp)) else Unit
        }
    if (infoOnTop) band()
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.weight(if (infoBand) 1f else 0.74f).fillMaxHeight().padding(start = 20.dp, top = if (infoOnTop) 8.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Guide", color = Jc.Title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(windowStart.day(), color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                error?.let { Text(it, color = Jc.Amber, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                guideNote?.let { Text(it, color = Jc.Focus, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                if (groups.size > 1) {
                    val shown = group.takeIf { it in groups } ?: groups.first()
                    // OK steps to the next group.
                    // The way in from the menu too, when the group has no channels to focus.
                    val way = if (channels?.isEmpty() == true) Modifier.focusRequester(entry) else Modifier
                    Button(
                        "Channels: $shown",
                        compact = true,
                        // Up here, the top rows are shown (▲ came from the first channel).
                        modifier = Modifier.padding(bottom = 2.dp).focusRequester(groupFocus).then(way).onFocusChanged { if (it.isFocused) guideScope.launch { listState.scrollToItem(0) } }
                            // ▼: the first channel's programme on now (Compose's own search lands rows lower).
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                val first = channels?.firstOrNull() ?: return@onPreviewKeyEvent false
                                val p = programs[first.id].orEmpty().firstOrNull { it.end.isAfter(windowStart) } ?: return@onPreviewKeyEvent false
                                cellFocus[p.id]?.let { it.tryFocus() } == true
                            },
                    ) {
                        Jdvr.prefs.changeChannelGroup(groups[(groups.indexOf(shown) + 1) % groups.size])
                    }
                }
            }
            val list = channels
            if (list == null) {
                Spacer(12)
                if (error == null) Text("Loading the guide…", color = Jc.Muted, fontSize = 16.sp)
                return@Column
            }
            if (list.isEmpty() && groups.size > 1 && group in groups) {
                Spacer(12)
                Text(
                    if (Jdvr.isNextPvr) "No channels in “$group”. Its channels are set in NextPVR's Channels settings."
                    else "No channels in “$group”. Mark channels as favourites in Jellyfin's web client.",
                    color = Jc.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
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
                        // The way back in (after the rail or a player): the programme picked to watch, else the last one on;
                        // opening the guide, what's on now on the channel last focused in it (else the one last watched).
                        val home =
                            (returnTo ?: focused ?: openingOn?.let { ch -> programs[ch].orEmpty().firstOrNull { it.end.isAfter(openingAt) } })
                                ?.takeIf { p -> list.any { it.id == p.channelId } }
                        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.onFocusChanged { gridHasFocus = it.hasFocus }) {
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
                                    onFocused = { onCellFocused(it) },
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
        if (!infoBand) {
            Box(Modifier.weight(0.26f).fillMaxHeight().background(guidePanel(0.6f))) {
                val p = selected
                if (p == null) {
                    focused?.let { InfoPanel(it, channels?.firstOrNull { c -> c.id == it.channelId }, symbols) }
                        ?: if (channels?.isEmpty() != true) Text("Move through the guide to see a programme here; OK for what you can do with it.", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(22.dp)) else Unit
                } else {
                    // Its actions open over the guide (below), docked or floating.
                    InfoPanel(p, channels?.firstOrNull { c -> c.id == p.channelId }, symbols)
                }
            }
        }
    }
    if (infoBelow) band()
    }
    // A programme's actions: a panel over the right of the grid, its buttons in two columns.
    selected?.let { p ->
        ProgramActionsHost(onDismiss = { closePanel() }) { Actions(p) }
    }
    airingsFor?.let { p ->
        AiringsDialog(
            programId = p.id,
            onNote = { msg ->
                guideNote = msg
                guideScope.launch {
                    delay(1500) // the server schedules in the background
                    reload++
                }
            },
            onDismiss = {
                airingsFor = null
                // Back to the programme it was opened from.
                guideScope.launch {
                    repeat(10) {
                        delay(60)
                        val r = cellFocus[p.id] ?: return@repeat
                        if (r.tryFocus()) return@launch
                    }
                }
            },
        )
    }
    }
    }
}

/** The guide's panels: see-through when background art is on (Settings), so the picture shows. */
@Composable
private fun guidePanel(alpha: Float = 0.78f): Color = if (Jdvr.prefs.guideArtSeconds > 0) Jc.Panel.seeThrough(alpha) else Jc.Panel

/**
 * Behind the guide: the rested-on programme's picture, faded in at the top right behind the
 * details; a portrait poster (no wide picture) fills the screen blurred instead.
 */
@Composable
private fun GuideArt(program: Program?) {
    // Its picture (NextPVR: fanart; Jellyfin: the guide's own, blurred when it's a poster).
    val url = program?.let { Jdvr.backend.imageUrl(it.id, 1080) }
    FadingBackdrop(program?.id, url, null)
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

private val BandHeight = 200.dp
private val PictureWidth = 240.dp

/** Nothing for today (the band's times are today's unless it says), "Tomorrow", else the date. */
private fun relativeDay(t: LocalDateTime): String? =
    when (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), t.toLocalDate())) {
        0L -> null
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> t.day()
    }

/**
 * The programme under the cursor on a band above or below the guide, laid out like Recorded Shows'
 * By date details: its picture (with how far along it is, when it's on now), the titles, the
 * channel's logo beside the when, the chips, and the description with what OK does.
 */
@Composable
private fun InfoBand(
    p: Program,
    channel: Channel?,
    symbols: MarkSymbols?,
) {
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    val now = LocalDateTime.now()
    val on = p.isOn(now)
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.width(PictureWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The channel's logo underneath: it shows when the programme has no picture.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Jc.Panel2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                channel?.logoUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.5f)) }
                AsyncImage(
                    model = Jdvr.backend.imageUrl(p.id, 300),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                )
            }
            if (on) {
                val total = Duration.between(p.start, p.end).toMinutes().coerceAtLeast(1)
                val done = Duration.between(p.start, now).toMinutes().coerceIn(0, total)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(p.start.time(), color = Jc.Muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    Box(Modifier.weight(1f).height(4.dp).background(Jc.Line, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total).background(Jc.Focus, RoundedCornerShape(2.dp)))
                    }
                    Text(p.end.time(), color = Jc.Muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, color = Jc.Title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            marks.title?.takeIf { it.isNotBlank() }?.let { Text(it, color = Jc.Text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                channel?.let { ChannelLogoTile(it) }
                val minutes = Duration.between(p.start, p.end).toMinutes()
                val first = listOfNotNull(p.seasonEpisode?.replace(":", " "), channel?.let { "${it.number} ${it.name}" }).joinToString(" · ")
                val second =
                    listOfNotNull(
                        // On now, the bar under the picture has the times.
                        if (on) null else listOfNotNull(relativeDay(p.start), "${p.start.time()} – ${p.end.time()}").joinToString(" "),
                        "$minutes min",
                        if (on) "${Duration.between(now, p.end).toMinutes().coerceAtLeast(1)} min left" else null,
                    ).joinToString(" · ")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (first.isNotEmpty()) Text(first, color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(second, color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GuideChips(p)
                if (p.isRepeat) Chip("repeat", Jc.Muted)
                if (p.isMovie) Chip("movie", Jc.Muted)
                MarkChips(marks)
            }
        }
        Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.overview ?: "", color = Jc.Text.copy(alpha = 0.85f), fontSize = 15.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (Jdvr.isNextPvr) "OK  Watch · record" else "OK  Watch · record · favourites", color = Jc.Muted, fontSize = 13.sp)
        }
    }
}

/** The channel's logo on a tile with its number under it; its name when it has no logo. */
@Composable
private fun ChannelLogoTile(c: Channel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.width(76.dp).height(40.dp).background(Jc.Panel2, RoundedCornerShape(7.dp)).padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (c.logoUrl != null) {
                AsyncImage(model = c.logoUrl, contentDescription = c.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text(c.name, color = Jc.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(c.number, color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The programme under the cursor: what it is, when, and what's planned for it. */
@Composable
private fun InfoPanel(
    p: Program,
    channel: Channel?,
    symbols: MarkSymbols?,
) {
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(p.name, color = Jc.Title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 3)
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
    symbols: MarkSymbols?,
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
    symbols: MarkSymbols?,
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
            focus.tryFocus()
            onFocusTaken()
        }
    }
    val marks = Marks.parse(program.rawEpisodeTitle, symbols)
    val extras = LocalGuideExtras.current
    // SageTV: red cells will record, green ones a Recording Favourite will; the focused one is glossy.
    val sage = Jc.Style == UiStyle.Sage
    // NextPVR: deep red cells will record; the focused cell turns green, or bright red if it records.
    val npvr = Jc.Style == UiStyle.NextPvr
    var cellFocused by remember { mutableStateOf(false) }
    val idle =
        when {
            sage && extras.isFavourite(program) -> Jc.Green.copy(alpha = 0.55f)
            sage && extras.realRecording(program) -> Jc.Recording.copy(alpha = 0.45f)
            npvr && extras.realRecording(program) -> Color(0xFF8F0A00)
            extras.realRecording(program) -> Jc.Recording.copy(alpha = 0.14f)
            else -> guidePanel()
        }
    val npvrFocus = if (extras.realRecording(program)) Color(0xFFD60E00) else Jc.Green
    // Dark type on the green, white on red and everywhere else.
    val darkType = npvr && cellFocused && !extras.realRecording(program)
    val titleColor = if (darkType) NextPvrInk else Jc.Text
    val subColor = if (darkType) NextPvrInk.copy(alpha = 0.75f) else if (npvr && cellFocused) Color.White.copy(alpha = 0.8f) else Jc.Muted
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .width((width - 4.dp).coerceAtLeast(8.dp))
                .padding(end = 4.dp)
                .fillMaxHeight()
                .focusRequester(focus)
                .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
                .onFocusChangedCompat {
                    cellFocused = it
                    if (it) onFocused()
                }
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
                containerColor = idle,
                focusedContainerColor = if (npvr) npvrFocus else Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = titleColor,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = if (npvr) Border.None else Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column((if (sage && cellFocused) Modifier.sageGloss() else Modifier).fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Red for a recording, two dots when a series records it; faint for a live TV buffer
                // (only kept if someone presses Record).
                if (program.isRecording) {
                    if (program.seriesTimerId != null && !extras.isBuffer(program)) SeriesDots(height = 8.dp)
                    else Box(Modifier.size(8.dp).background(Jc.Recording.copy(alpha = if (extras.isBuffer(program)) 0.35f else 1f), CircleShape))
                }
                Text(program.name, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (extras.scanning(program)) Text("CS", color = Jc.OnAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Jc.Amber, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
            }
            val sub = marks.title ?: if (program.isMovie) "Movie" else null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (marks.skipped) Text("■", color = Jc.Amber, fontSize = 12.sp)
                if (marks.otherAiring) Text("↪", color = Jc.Focus, fontSize = 12.sp)
                if (marks.inLibrary) Text("●", color = Jc.Green, fontSize = 12.sp)
                if (marks.watched) Text("✓", color = Jc.Green, fontSize = 12.sp)
                if (sub != null) Text(sub, color = subColor, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
