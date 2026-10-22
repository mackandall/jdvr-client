package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Where SageTV's menu was left, so coming back to Home lands on the same entry. */
private object SageMenuState {
    var main by mutableIntStateOf(0)
}

/** The same menu drawn in Gemstone's look: fanart behind black glass, one gem colour. */
private val LocalGemLook = androidx.compose.runtime.compositionLocalOf { false }

private class SageEntry(
    val key: String,
    val label: String,
    val subs: List<Pair<String, () -> Unit>>,
    // OK on the entry itself (else its first submenu entry).
    val open: (() -> Unit)? = null,
)

private val SageBlue = Color(0xFF4F86D8)
private val SageBox = Color(0xFF06122F).copy(alpha = 0.55f)
private val SageLabel = Color(0xFF8FB4E8)
private val GemLine = Color(0xFF2C3038)
private val GemBox = Color(0xFF08090C).copy(alpha = 0.6f)
private val GemGrey = Color(0xFF9AA0AA)

@Composable
private fun panelBorder() = if (LocalGemLook.current) GemLine else SageBlue

@Composable
private fun panelFill() = if (LocalGemLook.current) GemBox else SageBox

@Composable
private fun panelLabel() = if (LocalGemLook.current) Jc.Focus else SageLabel

private fun localOf(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

/**
 * Home in the SageTV themes: SageTV's main menu. ▲▼ move the selection (round from the bottom to
 * the top); OK opens the entry's first submenu item; ► shows the submenu and goes into it, ◄ back
 * out and the submenu goes away. The panels on the right show what the entry leads to. In
 * SageTV Gemstone the same menu sits on fanart behind black glass, in the gem colour.
 */
@Composable
fun SageHome(
    entry: FocusRequester,
    libraries: List<LiveTvRepository.Library>,
    showRecorded: Boolean,
    showTrash: Boolean,
    onOpenScreen: (Screen) -> Unit,
    onOpenGuide: (LocalDateTime?, UUID?) -> Unit,
    onOpenRecordings: (RecordingsPreset) -> Unit,
    onOpenLibrary: (UUID) -> Unit,
    onOpenMenuSettings: () -> Unit,
    onPlayChannel: (UUID) -> Unit,
    onSignOut: () -> Unit,
    onExit: () -> Unit,
) {
    val prefs = Jdvr.prefs
    val gem = Jc.Style == UiStyle.Gem
    var recordings by remember { mutableStateOf<List<LiveTvRepository.RecordingCard>>(emptyList()) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var upcoming by remember { mutableStateOf<List<LiveTvRepository.Upcoming>>(emptyList()) }
    var newCount by remember { mutableStateOf<Int?>(null) }
    var tuners by remember { mutableStateOf<LiveTvRepository.TunerStatus?>(null) }
    var drive by remember { mutableStateOf<LiveTvRepository.Drive?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var picking by remember { mutableStateOf(false) }
    var signOutArmed by remember { mutableStateOf(false) }
    LaunchedEffect(signOutArmed) {
        if (signOutArmed) {
            delay(4000)
            signOutArmed = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(5_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Jdvr.liveTv.channels() }.getOrNull()?.let { channels = it }
            upcoming = Jdvr.liveTv.schedule().orEmpty()
            Jdvr.liveTv.recordedShows()?.let { cards ->
                recordings = cards
                newCount = cards.sumOf { it.unplayedCount }
            }
            tuners = Jdvr.liveTv.tuners(null)
            drive = runCatching { Jdvr.liveTv.homeExtras() }.getOrNull()?.drive
            delay(60_000)
        }
    }
    val last = channels.firstOrNull { it.id.toString() == Jdvr.session.lastChannelId } ?: channels.firstOrNull()
    val recent =
        Jdvr.session.recentChannels.mapNotNull { id -> channels.firstOrNull { it.id.toString() == id } }
            .filter { it.id != last?.id }
            .take(4)
    val libs = libraries.inMenuOrder()
    val symbols = rememberMarkSymbols()

    // The menu: the Settings › Menu order, with SageTV's names; Home, Series and Trash live in submenus.
    val entries =
        buildList {
            prefs.menuOrder.filter { it !in prefs.hiddenMenu }.forEach { k ->
                when (k) {
                    "livetv" ->
                        add(
                            SageEntry(
                                k, "Live TV",
                                buildList {
                                    last?.let { c -> add("Last channel" to { onPlayChannel(c.id) }) }
                                    add("Channel list" to { onOpenScreen(Screen.LiveTv) })
                                    recent.forEach { c -> add("${c.number} ${c.name}" to { onPlayChannel(c.id) }) }
                                },
                            ),
                        )
                    "guide" ->
                        add(
                            SageEntry(
                                k, "Program Guide",
                                listOf(
                                    "Now" to { onOpenGuide(null, null) },
                                    "Tonight" to { onOpenGuide(LocalDateTime.of(now.toLocalDate(), LocalTime.of(20, 0)), null) },
                                    "Jump to a channel" to { picking = true },
                                ),
                            ),
                        )
                    "recorded" ->
                        if (showRecorded) {
                            add(
                                SageEntry(
                                    k, "Recorded Shows",
                                    buildList {
                                        add("All Recordings" to { onOpenRecordings(RecordingsPreset(view = Prefs.SHOWS, filter = Prefs.ALL)) })
                                        add("By Date" to { onOpenRecordings(RecordingsPreset(view = Prefs.BY_DATE)) })
                                        add("Unwatched" to { onOpenRecordings(RecordingsPreset(view = Prefs.SHOWS, unwatched = true)) })
                                        add("Archive" to { onOpenRecordings(RecordingsPreset(filter = Prefs.ARCHIVED)) })
                                        if (showTrash) add("Trash" to { onOpenScreen(Screen.Trash) })
                                    },
                                ),
                            )
                        }
                    "scheduled" ->
                        add(
                            SageEntry(
                                k, "Scheduled Recordings",
                                listOf(
                                    "Upcoming" to { onOpenScreen(Screen.Scheduled) },
                                    "Series" to { onOpenScreen(Screen.Series) },
                                    "Recording Favourites" to { onOpenScreen(Screen.Series) },
                                ),
                            ),
                        )
                    "libraries" ->
                        if (libs.isNotEmpty()) add(SageEntry(k, "Libraries", libs.map { lib -> lib.name to { onOpenLibrary(lib.id) } }))
                    "search" -> add(SageEntry(k, "Search", emptyList(), open = { onOpenScreen(Screen.Search) }))
                }
            }
            // Setup comes last and can't be hidden: without it there'd be no way to Settings.
            add(
                SageEntry(
                    "settings", "Setup",
                    listOf(
                        "Settings" to { onOpenScreen(Screen.Settings) },
                        "Menu order" to { onOpenMenuSettings() },
                        (if (signOutArmed) "Press again to sign out" else "Sign out") to { if (signOutArmed) onSignOut() else signOutArmed = true },
                        "Exit" to { onExit() },
                    ),
                ),
            )
        }
    if (entries.isEmpty()) return
    val mainIndex = SageMenuState.main.coerceIn(0, entries.lastIndex)
    val current = entries[mainIndex]
    // In the submenu: which entry (null: on the main column).
    var subIndex by remember { mutableStateOf<Int?>(null) }
    val subs = current.subs

    // OK on a main entry: its first submenu item (the default).
    fun activate() {
        val i = subIndex
        if (i != null) subs.getOrNull(i)?.second?.invoke() else current.open?.invoke() ?: subs.firstOrNull()?.second?.invoke()
    }

    // The library the Libraries panel shows (the one picked in the submenu, else the first).
    val panelLibrary = if (current.key == "libraries") libs.getOrNull(subIndex ?: 0) else null
    var libraryItems by remember(panelLibrary?.id) { mutableStateOf<List<LiveTvRepository.LibItem>?>(null) }
    LaunchedEffect(panelLibrary?.id) { panelLibrary?.let { libraryItems = Jdvr.liveTv.latestIn(it.id, 6) } }

    // Gemstone: the fanart for the entry.
    val fanart: String? =
        if (!gem) {
            null
        } else {
            when (current.key) {
                "livetv", "guide" -> last?.now?.let { Jdvr.liveTv.imageUrl(it.id, 1080) }
                "recorded" -> recordings.firstOrNull()?.id?.toUuid()?.let { Jdvr.liveTv.backdropUrl(it) }
                "scheduled" -> upcoming.firstNotNullOfOrNull { it.programId?.toUuid() }?.let { Jdvr.liveTv.imageUrl(it, 1080) }
                "libraries" -> libraryItems?.firstOrNull()?.let { Jdvr.liveTv.backdropUrl(it.seriesId ?: it.id) }
                else -> null
            }
        }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(entry)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val i = subIndex
                when (e.key) {
                    Key.DirectionUp -> {
                        if (i == null) SageMenuState.main = (mainIndex - 1 + entries.size) % entries.size else subIndex = (i - 1 + subs.size) % subs.size
                        true
                    }
                    Key.DirectionDown -> {
                        if (i == null) SageMenuState.main = (mainIndex + 1) % entries.size else subIndex = (i + 1) % subs.size
                        true
                    }
                    Key.DirectionRight -> {
                        if (i == null && subs.isNotEmpty()) subIndex = 0
                        true
                    }
                    Key.DirectionLeft -> {
                        subIndex = null
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // A held OK from the screen before repeats; only a fresh press opens.
                        if (e.nativeKeyEvent.repeatCount == 0) activate()
                        true
                    }
                    else -> false
                }
            }.focusable(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGemLook provides gem) {
        if (gem) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0F)))
            androidx.compose.animation.Crossfade(targetState = fanart, animationSpec = androidx.compose.animation.core.tween(600), label = "gem fanart") { url ->
                if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF508090C), Color(0xD908090C), Color(0x8C08090C)))))
        } else androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path =
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(-0.03f * w, 0.78f * h)
                    cubicTo(0.23f * w, 0.65f * h, 0.55f * w, 0.9f * h, 1.03f * w, 0.72f * h)
                }
            drawPath(path, Color(0xFF3E7BD6).copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            val path2 =
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(-0.03f * w, 0.82f * h)
                    cubicTo(0.26f * w, 0.69f * h, 0.56f * w, 0.96f * h, 1.03f * w, 0.78f * h)
                }
            drawPath(path2, Color(0xFF7FB2F0).copy(alpha = 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }

        // Top: the name, and the clock.
        Row(Modifier.padding(start = 36.dp, top = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gem) {
                Text("JDVR", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp)
            } else {
                Text(
                    "JDVR",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Shadow(Color(0xFF78B4FF).copy(alpha = 0.6f), blurRadius = 18f)),
                )
                Text("Home Theater", color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 36.dp, top = 22.dp), horizontalAlignment = Alignment.End) {
            Text(now.time(), color = Color.White, fontSize = 26.sp, fontWeight = if (gem) FontWeight.Light else FontWeight.SemiBold)
            Text(now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")).let { if (gem) it.uppercase() else it }, color = Jc.Muted, fontSize = 13.sp, letterSpacing = if (gem) 1.sp else 0.sp)
        }

        // The main menu.
        val itemHeight = 40.dp
        val itemGap = 5.dp
        // Gemstone's spaced capitals need a little more room.
        val menuWidth = if (gem) 284.dp else 250.dp
        Column(Modifier.padding(start = 82.dp, top = 108.dp).width(menuWidth), verticalArrangement = Arrangement.spacedBy(itemGap)) {
            entries.forEachIndexed { i, en ->
                val on = i == mainIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .then(
                            if (on && gem) {
                                // Gemstone: a gem outline on lit glass (lighter at the top, a bright top edge), glowing.
                                Modifier
                                    .shadow(if (subIndex == null) 12.dp else 3.dp, RoundedCornerShape(4.dp), ambientColor = Jc.Focus, spotColor = Jc.Focus)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF101217))
                                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f))))
                                    .drawBehind { drawRect(Color.White.copy(alpha = 0.28f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                                    .border(1.5.dp, Jc.Focus.copy(alpha = if (subIndex == null) 1f else 0.6f), RoundedCornerShape(4.dp))
                            } else if (on) {
                                Modifier
                                    .shadow(if (subIndex == null) 14.dp else 4.dp, RoundedCornerShape(8.dp), ambientColor = Color(0xFF50A0FF), spotColor = Color(0xFF50A0FF))
                                    .clip(RoundedCornerShape(8.dp))
                                    .sageGloss()
                                    .border(1.dp, Color(0xFF9CD0FF), RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            },
                        ).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                    if (gem) {
                        Text(
                            en.label.uppercase(),
                            color = if (on) Color.White else GemGrey,
                            fontSize = 16.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            en.label,
                            color = if (on) Color.White else Color(0xFFC9DAF2),
                            fontSize = 21.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                    }
                    // ► has something to open here: an arrow at the right end, on the highlighted entry only.
                    if (on && en.subs.isNotEmpty()) {
                        Text(
                            "▶",
                            color = Color.White,
                            fontSize = if (gem) 14.sp else 16.sp,
                            style = TextStyle(shadow = if (gem) Shadow(Jc.Focus, blurRadius = 12f) else Shadow(Color(0xFF001E5A).copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, 2f), 4f)),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    }
                }
            }
        }

        // Its submenu, level with the entry (kept on screen): only once ► has been pressed.
        val accent = if (gem) Jc.Focus else Color(0xFF5FB0FF)
        val accentLine = if (gem) Jc.Focus else Color(0xFF9CD0FF)
        if (subIndex != null && subs.isNotEmpty()) {
            val subRow = 30.dp
            val top = (108.dp + (itemHeight + itemGap) * mainIndex).coerceAtMost((490.dp - (subRow + 3.dp) * subs.size).coerceAtLeast(80.dp))
            Column(
                Modifier
                    .padding(start = 82.dp + menuWidth + 10.dp, top = top)
                    .width(196.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                subs.forEachIndexed { i, (label, _) ->
                    val on = subIndex == i
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(subRow)
                            .background(if (on) accent.copy(alpha = if (gem) 0.18f else 0.32f) else Color.Transparent, RoundedCornerShape(if (gem) 3.dp else 6.dp))
                            .then(if (on) Modifier.border(1.dp, accentLine, RoundedCornerShape(if (gem) 3.dp else 6.dp)) else Modifier)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, color = if (on) Color.White else if (gem) GemGrey else Color(0xFF9DB6DA), fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // The line joining the submenu to the menu.
            Box(
                Modifier
                    .padding(start = 82.dp + menuWidth + 2.dp, top = top)
                    .width(2.dp)
                    .height(subRow * subs.size + 3.dp * (subs.size - 1))
                    .background(if (gem) Jc.Focus.copy(alpha = 0.5f) else Color(0xFF3E7BD6)),
            )
        }

        // What the entry leads to.
        Column(Modifier.align(Alignment.TopEnd).padding(end = 36.dp, top = 108.dp).width(322.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (current.key) {
                "livetv" -> {
                    last?.let { LivePanel(it, now, "BACK TO LIVE", symbols) }
                    if (recent.isNotEmpty()) {
                        SagePanel("RECENT CHANNELS") { recent.forEach { c -> PanelLine("${c.number} ${c.name}", c.now?.name ?: "") } }
                    }
                }
                "guide" -> {
                    val start = channels.indexOfFirst { it.id == last?.id }.coerceAtLeast(0)
                    SagePanel("ON NOW") {
                        channels.drop((start - 2).coerceAtLeast(0)).take(8).forEach { c ->
                            PanelLine("${c.number}  ${c.now?.name ?: "—"}", c.name, bold = c.id == last?.id)
                        }
                    }
                }
                "recorded" -> {
                    if (gem) NewRecordingsPanel(recordings) else last?.let { LivePanel(it, now, "BACK TO LIVE", symbols) }
                    NextPanel(upcoming, now, 3)
                }
                "scheduled" -> {
                    val recording = upcoming.filter { it.recordingNow }
                    if (recording.isNotEmpty()) {
                        SagePanel("RECORDING NOW") { recording.take(3).forEach { u -> PanelLine("● " + u.name, listOfNotNull(u.channelNumber, u.channelName).joinToString(" "), red = true) } }
                    }
                    NextPanel(upcoming, now, 6)
                }
                "libraries" -> panelLibrary?.let { LibraryPanel(it, libraryItems) }
                "search" -> SagePanel("SEARCH") { Text("Find shows and films in every library, and programmes in the guide.", color = Jc.Text, fontSize = 15.sp) }
                "settings" -> {
                    SagePanel("TUNERS") {
                        val t = tuners
                        if (t == null) {
                            Text("Tuner status needs the JellyDVR Schedule plugin.", color = Jc.Muted, fontSize = 14.sp)
                        } else {
                            PanelLine("${t.busy.size} of ${t.capacity} in use", "")
                            t.busy.take(4).forEach { b -> PanelLine("${b.number} ${b.channelName}", (b.recording.firstOrNull()?.let { "recording $it" } ?: b.watching.firstOrNull()?.let { "watching" }) ?: "") }
                        }
                    }
                }
            }
        }

        // Bottom: how things stand.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(42.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, (if (gem) Color(0xFF08090C) else Color(0xFF040C20)).copy(alpha = 0.85f))))
                .padding(horizontal = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts =
                listOfNotNull(
                    newCount?.let { "$it new recording" + if (it == 1) "" else "s" },
                    tuners?.let { "${it.busy.size} of ${it.capacity} tuners in use" },
                )
            Text(parts.joinToString(" · "), color = Jc.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            drive?.takeIf { it.totalBytes > 0 }?.let { Text("Recordings drive: ${it.freeBytes / 1_000_000_000L} GB free", color = Jc.Muted, fontSize = 14.sp) }
        }
        }
    }

    // Jump to a channel: pick one, the guide opens on it.
    if (picking) {
        BackHandler { picking = false }
        ChannelPicker(channels, last?.id, onPick = {
            picking = false
            onOpenGuide(null, it)
        })
    }
}

@Composable
private fun SagePanel(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(panelFill(), RoundedCornerShape(if (LocalGemLook.current) 4.dp else 8.dp))
            .border(1.dp, panelBorder(), RoundedCornerShape(if (LocalGemLook.current) 4.dp else 8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, color = panelLabel(), fontSize = 11.sp, letterSpacing = if (LocalGemLook.current) 2.sp else 1.sp)
        content()
    }
}

@Composable
private fun PanelLine(
    left: String,
    right: String,
    bold: Boolean = false,
    red: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = if (red) Jc.Recording else Color.White, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (right.isNotEmpty()) Text(right, color = Jc.Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The last channel: its programme's picture (no tuner is used), what's on, how far in. */
@Composable
private fun LivePanel(
    c: Channel,
    now: LocalDateTime,
    label: String,
    symbols: LiveTvRepository.MarkSymbols?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (LocalGemLook.current) 4.dp else 8.dp))
            .background(panelFill())
            .border(1.dp, panelBorder(), RoundedCornerShape(if (LocalGemLook.current) 4.dp else 8.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(if (LocalGemLook.current) Color(0xFF14161B) else Color(0xFF050B1C)), contentAlignment = Alignment.Center) {
            val p = c.now
            val image = p?.let { Jdvr.liveTv.imageUrl(it.id, 360) } ?: c.logoUrl
            if (image != null) AsyncImage(model = image, contentDescription = null, contentScale = if (p != null) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$label · ${c.number} ${c.name}".uppercase(), color = panelLabel(), fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
            val p = c.now
            Text(p?.name ?: c.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val marks = Marks.parse(p?.rawEpisodeTitle, symbols)
            listOfNotNull(p?.seasonEpisode, marks.title).joinToString(" · ").ifEmpty { null }?.let { Text(it, color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (p != null) {
                val total = Duration.between(p.start, p.end).toMinutes().coerceAtLeast(1)
                val done = Duration.between(p.start, now).toMinutes().coerceIn(0, total)
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(if (LocalGemLook.current) 3.dp else 5.dp).background(if (LocalGemLook.current) GemLine else Color(0xFF1B3566), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(done.toFloat() / total).height(if (LocalGemLook.current) 3.dp else 5.dp).background(if (LocalGemLook.current) Jc.Focus else Color(0xFF5FB0FF), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

@Composable
private fun NextPanel(
    upcoming: List<LiveTvRepository.Upcoming>,
    now: LocalDateTime,
    count: Int,
) {
    val next = upcoming.filter { !it.recordingNow }.take(count)
    SagePanel("RECORDING NEXT") {
        if (next.isEmpty()) Text("Nothing scheduled.", color = Jc.Muted, fontSize = 14.sp)
        next.forEach { u ->
            val start = localOf(u.start)
            val time = start?.let { if (it.toLocalDate() == now.toLocalDate()) it.time() else it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.time() } ?: ""
            PanelLine("● " + u.name + (u.episodeTitle?.let { " · $it" } ?: ""), time)
        }
    }
}

/** Gemstone's Recorded Shows panel: the newest recordings' posters. */
@Composable
private fun NewRecordingsPanel(cards: List<LiveTvRepository.RecordingCard>) {
    SagePanel("NEW RECORDINGS") {
        val fresh = cards.filter { it.unplayedCount > 0 }.ifEmpty { cards }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fresh.take(3).forEach { c ->
                AsyncImage(
                    model = Jdvr.liveTv.imageUrl(c.imageId ?: c.id, 240),
                    contentDescription = c.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(90.dp).height(135.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF14161B)),
                )
            }
        }
        fresh.take(3).forEach { PanelLine(it.name, if (it.unplayedCount > 1) "${it.unplayedCount} new" else "") }
    }
}

@Composable
private fun LibraryPanel(
    lib: LiveTvRepository.Library,
    items: List<LiveTvRepository.LibItem>?,
) {
    SagePanel("RECENTLY ADDED · ${lib.name.uppercase()}") {
        val list = items
        if (list == null) {
            Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.take(3).forEach { it ->
                    AsyncImage(
                        model = Jdvr.liveTv.imageUrl(it.seriesId ?: it.id, 240),
                        contentDescription = it.seriesName ?: it.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(90.dp).height(135.dp).clip(RoundedCornerShape(5.dp)).background(panelFill()),
                    )
                }
            }
            list.forEach { PanelLine(it.seriesName ?: it.name, it.seasonEpisode ?: it.year?.toString() ?: "") }
        }
    }
}

/** SageTV's channel list for "Jump to a channel": OK opens the guide there. */
@Composable
private fun ChannelPicker(
    channels: List<Channel>,
    current: UUID?,
    onPick: (UUID) -> Unit,
) {
    val first = remember { FocusRequester() }
    val gem = Jc.Style == UiStyle.Gem
    val start = channels.indexOfFirst { it.id == current }.coerceAtLeast(0)
    val state = androidx.compose.foundation.lazy.rememberLazyListState((start - 3).coerceAtLeast(0))
    LaunchedEffect(channels.isNotEmpty()) {
        repeat(30) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF040C20).copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(440.dp)
                .height(420.dp)
                .background(Jc.Sheet, RoundedCornerShape(10.dp))
                .border(1.dp, if (gem) GemLine else SageBlue, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Jump to a channel", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                    var focused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onPick(c.id) },
                        modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier).onFocusChangedCompat { focused = it },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent, contentColor = Color.White, focusedContentColor = Color.White),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, if (gem) Jc.Focus else Color(0xFF9CD0FF)))),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(
                            (if (focused && gem) Modifier.background(Jc.Focus.copy(alpha = 0.18f)) else if (focused) Modifier.sageGloss() else Modifier).fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.number, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                            Text(c.name, fontSize = 15.sp, color = if (focused) Color.White else Color(0xFFC9DAF2), modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.now?.name ?: "", fontSize = 14.sp, color = if (focused) Color.White else Jc.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
