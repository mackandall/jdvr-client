package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
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
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.Upcoming
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Where SageTV's menu was left, so coming back to Home lands on the same entry. */
private object SageMenuState {
    var main by mutableIntStateOf(0)
}

private class SageEntry(
    val key: String,
    val label: String,
    val subs: List<Pair<String, () -> Unit>>,
    // OK on the entry itself (else its first submenu entry).
    val open: (() -> Unit)? = null,
)

/**
 * Home in the SageTV themes: SageTV's main menu. ▲▼ move the selection (round from the bottom to
 * the top); OK opens the entry's first submenu item; ► shows the submenu and goes into it, ◄ back
 * out and the submenu goes away. The panels on the right show what the entry leads to. In
 * SageTV Gemstone the same menu sits on fanart behind black glass, in the gem colour.
 */
@Composable
fun SageHome(
    entry: FocusRequester,
    libraries: List<Library>,
    showRecorded: Boolean,
    showTrash: Boolean,
    onOpenScreen: (Screen) -> Unit,
    onOpenGuide: (LocalDateTime?, UUID?) -> Unit,
    onOpenRecordings: (RecordingsPreset) -> Unit,
    onOpenLibrary: (UUID) -> Unit,
    onOpenMenuSettings: () -> Unit,
    onPlayChannel: (UUID) -> Unit,
    onSignOut: () -> Unit,
    onSwitchServer: () -> Unit = {},
    onExit: () -> Unit,
) {
    val prefs = Jdvr.prefs
    val gem = Jc.Style == UiStyle.Gem
    val data = rememberHomeData()
    val recordings = data.shows.orEmpty()
    val channels = data.channels.orEmpty()
    val upcoming = data.schedule
    val newCount = data.shows?.sumOf { it.unplayedCount }
    val tuners = data.tuners
    val drive = data.extras?.drive
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
    val last = data.lastChannel
    val recent =
        Jdvr.session.recentChannels
            .mapNotNull { id -> channels.firstOrNull { it.id.toString() == id } }
            .filter { it.id != last?.id }
            .take(4)
    val libs = libraries.inMenuOrder()
    val symbols = rememberMarkSymbols()
    // What this TV may not do (JellyDVR Space's device rights) isn't in its menu, as in the rail.
    val rights = deviceRights()

    // The menu: the Settings › Menu order, with SageTV's names; Home, Series and Trash live in submenus.
    val entries =
        buildList {
            prefs.menuOrder.filter { it !in prefs.hiddenMenu }.forEach { k ->
                when (k) {
                    "livetv" -> {
                        if (rights.liveTv) {
                            add(
                                SageEntry(
                                    k,
                                    "Live TV",
                                    buildList {
                                        last?.let { c -> add("Last channel" to { onPlayChannel(c.id) }) }
                                        add("Channel list" to { onOpenScreen(Screen.LiveTv) })
                                        recent.forEach { c -> add("${c.number} ${c.name}" to { onPlayChannel(c.id) }) }
                                    },
                                ),
                            )
                        }
                    }

                    "guide" -> {
                        add(
                            SageEntry(
                                k,
                                "Program Guide",
                                listOf(
                                    "Now" to { onOpenGuide(null, null) },
                                    "Tonight" to { onOpenGuide(LocalDateTime.of(now.toLocalDate(), LocalTime.of(20, 0)), null) },
                                    "Jump to a channel" to { picking = true },
                                ),
                            ),
                        )
                    }

                    "recorded" -> {
                        if (showRecorded) {
                            add(
                                SageEntry(
                                    k,
                                    "Recorded Shows",
                                    buildList {
                                        add("All Recordings" to { onOpenRecordings(RecordingsPreset(view = Prefs.SHOWS, filter = Prefs.ALL)) })
                                        add("By Date" to { onOpenRecordings(RecordingsPreset(view = Prefs.BY_DATE)) })
                                        add("Unwatched" to { onOpenRecordings(RecordingsPreset(view = Prefs.SHOWS, filter = Prefs.ALL, unwatched = true)) })
                                        add("Archive" to { onOpenRecordings(RecordingsPreset(filter = Prefs.ARCHIVED)) })
                                        if (showTrash) add("Trash" to { onOpenScreen(Screen.Trash) })
                                    },
                                ),
                            )
                        }
                    }

                    "scheduled" -> {
                        add(
                            SageEntry(
                                k,
                                "Scheduled Recordings",
                                buildList {
                                    add("Upcoming" to { onOpenScreen(Screen.Scheduled) })
                                    // One screen has both: Jellyfin's series recordings and Schedule's favourites.
                                    if (rights.record) add("Series & Favourites" to { onOpenScreen(Screen.Series) })
                                },
                            ),
                        )
                    }

                    "libraries" -> {
                        if (libs.isNotEmpty()) add(SageEntry(k, "Libraries", libs.map { lib -> lib.name to { onOpenLibrary(lib.id) } }))
                    }

                    "search" -> {
                        add(SageEntry(k, "Search", emptyList(), open = { onOpenScreen(Screen.Search) }))
                    }
                }
            }
            // Setup comes last and can't be hidden: without it there'd be no way to Settings.
            add(
                SageEntry(
                    "settings",
                    "Setup",
                    listOf(
                        "Settings" to { onOpenScreen(Screen.Settings) },
                        "Menu order" to { onOpenMenuSettings() },
                        "Switch server" to { onSwitchServer() },
                        (if (signOutArmed) "Press again to sign out" else "Sign out") to { if (signOutArmed) onSignOut() else signOutArmed = true },
                        "Exit" to { onExit() },
                    ),
                ),
            )
        }
    if (entries.isEmpty()) return
    val mainIndex = SageMenuState.main.coerceIn(0, entries.lastIndex)
    val current = entries[mainIndex]
    // In the submenu: which entry (null: on the main column). Back to the main column whenever the
    // entry changes underneath (libraries or rights arriving add entries above it).
    var subIndex by remember(current.key) { mutableStateOf<Int?>(null) }
    val subs = current.subs

    // OK on a main entry: its first submenu item (the default).
    fun activate() {
        val i = subIndex
        if (i != null) subs.getOrNull(i)?.second?.invoke() else current.open?.invoke() ?: subs.firstOrNull()?.second?.invoke()
    }

    // The library the Libraries panel shows (the one picked in the submenu, else the first).
    val panelLibrary = if (current.key == "libraries") libs.getOrNull(subIndex ?: 0) else null
    var libraryItems by remember(panelLibrary?.id) { mutableStateOf<List<LibItem>?>(null) }
    LaunchedEffect(panelLibrary?.id) { panelLibrary?.let { libraryItems = Jdvr.backend.latestIn(it.id, 6) } }

    // Gemstone: the fanart for the entry.
    val fanart: String? =
        if (!gem) {
            null
        } else {
            when (current.key) {
                "livetv", "guide" -> {
                    last?.now?.let { Jdvr.backend.imageUrl(it.id, 1080) }
                }

                "recorded" -> {
                    recordings
                        .firstOrNull()
                        ?.id
                        ?.toUuid()
                        ?.let { Jdvr.backend.backdropUrl(it) }
                }

                "scheduled" -> {
                    upcoming.orEmpty().firstNotNullOfOrNull { it.programId?.toUuid() }?.let { Jdvr.backend.imageUrl(it, 1080) }
                }

                "libraries" -> {
                    libraryItems?.firstOrNull()?.let { Jdvr.backend.backdropUrl(it.seriesId ?: it.id) }
                }

                else -> {
                    null
                }
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
                        if (i == null || subs.isEmpty()) SageMenuState.main = (mainIndex - 1 + entries.size) % entries.size else subIndex = (i - 1 + subs.size) % subs.size
                        true
                    }

                    Key.DirectionDown -> {
                        if (i == null || subs.isEmpty()) SageMenuState.main = (mainIndex + 1) % entries.size else subIndex = (i + 1) % subs.size
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

                    else -> {
                        false
                    }
                }
            }.focusable(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGemLook provides gem) {
            if (gem) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0C0F)))
                androidx.compose.animation.Crossfade(
                    targetState = fanart,
                    animationSpec =
                        androidx.compose.animation.core
                            .tween(600),
                    label = "gem fanart",
                ) { url ->
                    if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush
                            .horizontalGradient(listOf(Color(0xF508090C), Color(0xD908090C), Color(0x8C08090C))),
                    ),
                )
            } else {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val path =
                        androidx.compose.ui.graphics.Path().apply {
                            moveTo(-0.03f * w, 0.78f * h)
                            cubicTo(0.23f * w, 0.65f * h, 0.55f * w, 0.9f * h, 1.03f * w, 0.72f * h)
                        }
                    drawPath(
                        path,
                        Color(0xFF3E7BD6).copy(alpha = 0.35f),
                        style =
                            androidx.compose.ui.graphics.drawscope
                                .Stroke(2.dp.toPx()),
                    )
                    val path2 =
                        androidx.compose.ui.graphics.Path().apply {
                            moveTo(-0.03f * w, 0.82f * h)
                            cubicTo(0.26f * w, 0.69f * h, 0.56f * w, 0.96f * h, 1.03f * w, 0.78f * h)
                        }
                    drawPath(
                        path2,
                        Color(0xFF7FB2F0).copy(alpha = 0.18f),
                        style =
                            androidx.compose.ui.graphics.drawscope
                                .Stroke(1.5.dp.toPx()),
                    )
                }
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
                Text(
                    now
                        .format(
                            java.time.format.DateTimeFormatter
                                .ofPattern("EEEE, MMMM d"),
                        ).let { if (gem) it.uppercase() else it },
                    color = Jc.Muted,
                    fontSize = 13.sp,
                    letterSpacing = if (gem) 1.sp else 0.sp,
                )
            }

            // The main menu.
            val itemHeight = 40.dp
            val itemGap = 5.dp
            // Room for the longest entry in bold (Scheduled Recordings) and its ▶.
            val menuWidth = 290.dp
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
                                        .background(
                                            androidx.compose.ui.graphics.Brush
                                                .verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f))),
                                        ).drawBehind {
                                            drawRect(
                                                Color.White.copy(alpha = 0.28f),
                                                size =
                                                    androidx.compose.ui.geometry
                                                        .Size(size.width, 1.dp.toPx()),
                                            )
                                        }.border(1.5.dp, Jc.Focus.copy(alpha = if (subIndex == null) 1f else 0.6f), RoundedCornerShape(4.dp))
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
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // ► has something to open here: an arrow at the right end, on the highlighted entry only.
                            if (on && en.subs.isNotEmpty()) {
                                Text(
                                    "▶",
                                    color = Color.White,
                                    fontSize = if (gem) 14.sp else 16.sp,
                                    style =
                                        TextStyle(
                                            shadow =
                                                if (gem) {
                                                    Shadow(Jc.Focus, blurRadius = 12f)
                                                } else {
                                                    Shadow(
                                                        Color(0xFF001E5A).copy(alpha = 0.6f),
                                                        androidx.compose.ui.geometry
                                                            .Offset(0f, 2f),
                                                        4f,
                                                    )
                                                },
                                        ),
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
                            Text(
                                label,
                                color =
                                    if (on) {
                                        Color.White
                                    } else if (gem) {
                                        GemGrey
                                    } else {
                                        Color(0xFF9DB6DA)
                                    },
                                fontSize = 15.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                        val recording = upcoming.orEmpty().filter { it.recordingNow }
                        if (recording.isNotEmpty()) {
                            SagePanel("RECORDING NOW") { recording.take(3).forEach { u -> PanelLine("● " + u.name, listOfNotNull(u.channelNumber, u.channelName).joinToString(" "), red = true) } }
                        }
                        NextPanel(upcoming, now, 6)
                    }

                    "libraries" -> {
                        panelLibrary?.let { LibraryPanel(it, libraryItems) }
                    }

                    "search" -> {
                        SagePanel("SEARCH") { Text("Find shows and films in every library, and programmes in the guide.", color = Jc.Text, fontSize = 15.sp) }
                    }

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
                    .background(
                        androidx.compose.ui.graphics.Brush
                            .verticalGradient(listOf(Color.Transparent, (if (gem) Color(0xFF08090C) else Color(0xFF040C20)).copy(alpha = 0.85f))),
                    ).padding(horizontal = 36.dp),
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
        BackHandler {
            picking = false
            // The picker's row had focus; give it back to the menu.
            entry.tryFocus()
        }
        ChannelPicker(channels, last?.id, onPick = {
            picking = false
            onOpenGuide(null, it)
        })
    }
}
