package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.RecordingCard
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/*
 * Home in the look-alike themes (JDVR Embuary, Channels JDVR, JDVR Estuary): the same things as the
 * tile Home (what's on next, new recordings, where you left off) laid out the way each of those
 * apps lays out its own Home. The slide-out menu stays (◄ at the left edge, or Back).
 */

/** One card in a row: a recording, a show, something scheduled, or something half watched. */
private class LookCard(
    val key: String,
    val title: String,
    val sub: String?,
    val image: String?,
    // A corner tag: REC (red), a count or NEW.
    val badge: String? = null,
    val badgeRed: Boolean = false,
    // How far in, 0–1 (Continue watching).
    val progress: Float = 0f,
    val onClick: () -> Unit,
)

/** A place to go: a screen or a library. */
private class LookNav(
    val key: String,
    val label: String,
    val icon: MenuIcon,
    val sub: String? = null,
    val go: () -> Unit,
)

private class LookRow(
    val title: String,
    val cards: List<LookCard>,
)

private class LookData(
    val now: LocalDateTime,
    val rows: List<LookRow>,
    // Recordings, Live TV, Guide… (no Home: this is it).
    val nav: List<LookNav>,
    val loaded: Boolean,
)

private fun localOf(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

@Composable
private fun rememberLookData(
    libraries: List<Library>,
    showRecorded: Boolean,
    onPlayRecording: (PlayRecording) -> Unit,
    onOpenScreen: (Screen) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenLibrary: (UUID) -> Unit,
): LookData {
    val data = rememberHomeData()
    val scope = rememberCoroutineScope()
    val rights = deviceRights()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(5_000)
        }
    }
    // Where the user left off, and (without Episode History) the libraries' newest.
    var resume by remember { mutableStateOf<List<LibItem>?>(null) }
    var recentlyAdded by remember { mutableStateOf<List<RecordingCard>?>(null) }
    LaunchedEffect(data.round) {
        if (!data.loaded) return@LaunchedEffect
        // Asked together.
        launch { Jdvr.backend.resumeItems(12)?.let { resume = it } }
        if (data.shows == null) launch { recentlyAdded = Jdvr.backend.recentlyAdded(12) ?: recentlyAdded.orEmpty() }
    }
    val bufferIds = data.bufferIds

    val continueCards =
        resume.orEmpty().map { i ->
            val total = (i.runtimeMin ?: 0L) * 60_000L
            val left = if (total > 0) ((total - i.resumeMs) / 60_000L).coerceAtLeast(1L) else null
            LookCard(
                key = "r${i.id}",
                title = i.seriesName ?: i.name,
                sub =
                    if (i.seriesName != null) {
                        listOfNotNull(i.seasonEpisode, i.name).joinToString(" · ")
                    } else {
                        left?.let { "$it min left" } ?: i.year?.toString()
                    },
                image = Jdvr.backend.imageUrl(i.id, 300),
                progress = if (total > 0) (i.resumeMs.toFloat() / total).coerceIn(0.02f, 1f) else 0f,
            ) { onPlayRecording(PlayRecording(i.id)) }
        }
    val recentCards =
        (data.shows?.take(14) ?: recentlyAdded.orEmpty()).map { c ->
            LookCard(
                key = "s${c.id}",
                title = c.name,
                sub = c.subtitle,
                image = Jdvr.backend.imageUrl(c.imageId ?: c.id, 300),
                badge =
                    when {
                        c.recordingCount > 0 -> "REC"
                        c.unplayedCount > 1 -> c.unplayedCount.toString()
                        c.unplayedCount == 1 -> "NEW"
                        else -> null
                    },
                badgeRed = c.recordingCount > 0,
            ) { if (c.type == "Series") onOpenShow(c.id) else c.id.toUuid()?.let { onPlayRecording(PlayRecording(it)) } }
        }
    val tonightCards =
        data.schedule
            .orEmpty()
            .filter { it.programId?.plainId() !in bufferIds }
            .take(14)
            .map { u ->
                val start = localOf(u.start)
                val at =
                    start?.let {
                        if (it.toLocalDate() == now.toLocalDate()) {
                            it.time()
                        } else {
                            it.dayOfWeek.name
                                .take(3)
                                .lowercase()
                                .replaceFirstChar { c -> c.uppercase() } + " " + it.time()
                        }
                    }
                LookCard(
                    key = "u${u.timerId}",
                    title = u.name,
                    sub = listOfNotNull(if (u.recordingNow) "Recording now" else at, u.channelName ?: u.channelNumber).joinToString(" · "),
                    image = u.programId?.let { Jdvr.backend.imageUrl(it, 300) },
                    badge = if (u.recordingNow) "REC" else null,
                    badgeRed = true,
                ) {
                    if (u.recordingNow) {
                        // Watch it from the start.
                        scope.launch {
                            val pid = u.programId?.toUuid() ?: return@launch
                            val channel = attempt { Jdvr.backend.program(pid)?.channelId }
                            Jdvr.backend.recordingOfProgram(pid)?.let { onPlayRecording(PlayRecording(it, startMs = 0L, liveChannelId = channel)) }
                        }
                    } else {
                        onOpenScreen(Screen.Scheduled)
                    }
                }
            }
    val rows =
        listOf(
            LookRow("Continue watching", continueCards),
            LookRow(if (data.shows == null) "Recently added" else "Recently recorded", recentCards),
            LookRow("Recording next", tonightCards),
        ).filter { it.cards.isNotEmpty() }

    val unwatched = data.shows?.sumOf { it.unplayedCount }
    val nav =
        buildList {
            if (showRecorded) add(LookNav("recorded", "Recordings", MenuIcon.Recorded, sub = unwatched?.let { "$it unwatched" }) { onOpenScreen(Screen.RecordedShows) })
            if (rights.liveTv) add(LookNav("livetv", "Live TV", MenuIcon.LiveTv) { onOpenScreen(Screen.LiveTv) })
            add(LookNav("guide", "Guide", MenuIcon.Guide) { onOpenScreen(Screen.Guide) })
            add(LookNav("scheduled", "Scheduled", MenuIcon.Scheduled) { onOpenScreen(Screen.Scheduled) })
            if (rights.record) add(LookNav("series", "Series & Favourites", MenuIcon.Series) { onOpenScreen(Screen.Series) })
            libraries.inMenuOrder().forEach { lib -> add(LookNav("lib${lib.id}", lib.name, lib.menuIcon()) { onOpenLibrary(lib.id) }) }
            add(LookNav("search", "Search", MenuIcon.Search) { onOpenScreen(Screen.Search) })
            add(LookNav("settings", "Settings", MenuIcon.Settings) { onOpenScreen(Screen.Settings) })
        }
    return LookData(now, rows, nav, data.loaded)
}

/** Home for whichever look-alike theme is on. */
@Composable
fun LookalikeHome(
    entry: FocusRequester,
    libraries: List<Library>,
    showRecorded: Boolean,
    onPlayRecording: (PlayRecording) -> Unit,
    onOpenScreen: (Screen) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenLibrary: (UUID) -> Unit,
) {
    val d = rememberLookData(libraries, showRecorded, onPlayRecording, onOpenScreen, onOpenShow, onOpenLibrary)
    when (Jc.Style) {
        UiStyle.Channels -> ChannelsHome(entry, d)
        UiStyle.Estuary -> EstuaryHome(entry, d)
        else -> EmbuaryHome(entry, d)
    }
}

private val Line = IconSets.first()

@Composable
private fun Glyph(
    icon: MenuIcon,
    size: Dp,
    color: Color,
) = MenuGlyph(icon, Line, size, color, color)

/** A card's picture, or its name written large on a dark plate when it has none. */
@Composable
private fun CardArt(
    c: LookCard,
    nameSize: Int,
) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2B3B55), Color(0xFF141C2A)))), contentAlignment = Alignment.Center) {
        Text(
            c.title.uppercase(),
            color = Color.White,
            fontSize = nameSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
        c.image?.let { AsyncImage(model = it, contentDescription = c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

private fun Modifier.entryIf(
    yes: Boolean,
    entry: FocusRequester,
) = if (yes) focusRequester(entry) else this

// ───────────────────────────── JDVR Embuary ─────────────────────────────

@Composable
private fun EmbuaryHome(
    entry: FocusRequester,
    d: LookData,
) {
    val accent = Jc.Green
    Column(Modifier.fillMaxSize()) {
        // The thin top bar: the app's name, the main places, the clock.
        Row(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color(0xD9061022))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("☰", color = Color.White, fontSize = 13.sp)
            Text("jdvr", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Home", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp))
                d.nav.filter { it.key in setOf("recorded", "livetv", "guide", "scheduled") }.forEach { n -> EmbuaryTopLink(n.label, n.go) }
            }
            EmbuaryTopLink("Search", d.nav.firstOrNull { it.key == "search" }?.go ?: {})
            Text(d.now.time(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Focus starts on the first of My media (it's there before the rows have loaded).
            Text("My media", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.nav.forEachIndexed { i, n -> EmbuaryMediaTile(n, Modifier.entryIf(i == 0, entry)) }
            }
            if (d.rows.isEmpty()) Text(if (d.loaded) "Nothing new yet." else "Loading…", color = Jc.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp))
            d.rows.forEach { row ->
                Text(row.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.cards.forEach { c -> EmbuaryCard(c, accent, Modifier) }
                }
            }
        }
    }
}

@Composable
private fun EmbuaryTopLink(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(3.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.16f), contentColor = Color(0xFF9FB0C8), focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)) }
}

@Composable
private fun EmbuaryMediaTile(
    n: LookNav,
    modifier: Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(96.dp)) {
        Surface(
            onClick = n.go,
            modifier = modifier.width(96.dp).height(54.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(3.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f), focusedContainerColor = Color.White.copy(alpha = 0.18f)),
            border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))), focusedBorder = Border(BorderStroke(3.dp, Color.White))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glyph(n.icon, 22.dp, Color(0xFFDFE7F2)) } }
        Text(n.label, color = Color(0xFFE4EBF5), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmbuaryCard(
    c: LookCard,
    accent: Color,
    modifier: Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(150.dp)) {
        Surface(
            onClick = c.onClick,
            modifier = modifier.width(150.dp).height(84.dp),
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C2A44), focusedContainerColor = Color(0xFF1C2A44)),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                CardArt(c, 14)
                c.badge?.let { b ->
                    Text(
                        b,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(if (c.badgeRed) Jc.Recording else accent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (c.progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(c.progress)
                            .height(3.dp)
                            .background(accent),
                    )
                }
            }
        }
        Text(c.title, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.sub?.let { Text(it, color = Color(0xFFA9B8CC), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// ───────────────────────────── Channels JDVR ─────────────────────────────

@Composable
private fun ChannelsHome(
    entry: FocusRequester,
    d: LookData,
) {
    // The heading says where the icon strip's focused icon goes.
    var heading by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize()) {
        // The icon strip.
        Column(
            Modifier
                .width(58.dp)
                .fillMaxHeight()
                .padding(top = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelsStripIcon(MenuIcon.Home, selected = true, onFocus = { heading = if (it) "Home" else null }) {}
            d.nav.filter { it.key != "settings" }.take(7).forEach { n -> ChannelsStripIcon(n.icon, selected = false, onFocus = { heading = if (it) n.label else null }, onClick = n.go) }
            d.nav.firstOrNull { it.key == "settings" }?.let { n -> ChannelsStripIcon(n.icon, selected = false, onFocus = { heading = if (it) n.label else null }, onClick = n.go) }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, end = 58.dp)) {
                Text(heading ?: "Home", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                Text(d.now.time(), color = Color(0xFFD6CDEE), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 40.dp))
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val firstRow = d.rows.firstOrNull()
                if (d.rows.isEmpty()) Text(if (d.loaded) "Nothing new yet." else "Loading…", color = Jc.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp))
                d.rows.forEach { row ->
                    Text(if (row.title == "Continue watching") "Up Next" else row.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 30.dp, top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.cards.forEachIndexed { i, c -> ChannelsCard(c, Modifier.entryIf(row === firstRow && i == 0, entry)) }
                    }
                }
                Text("Browse", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 30.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.nav.filter { it.key != "settings" && it.key != "search" }.forEachIndexed { i, n -> ChannelsCategory(n, Modifier.entryIf(d.loaded && firstRow == null && i == 0, entry)) }
                }
            }
        }
    }
}

@Composable
private fun ChannelsStripIcon(
    icon: MenuIcon,
    selected: Boolean,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier =
            Modifier.size(38.dp).onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0xFF4A2F84)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Glyph(
                icon,
                20.dp,
                if (focused) {
                    Color.White
                } else if (selected) {
                    Color(0xFFA77BFF)
                } else {
                    Color(0xFFA99CC8)
                },
            )
        }
    }
}

@Composable
private fun ChannelsCard(
    c: LookCard,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(184.dp)) {
        Surface(
            onClick = c.onClick,
            modifier = modifier.width(184.dp).height(104.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF3B2B63), focusedContainerColor = Color(0xFF3B2B63)),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(6.dp))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                CardArt(c, 15)
                c.badge?.let { b ->
                    val red = c.badgeRed
                    Text(
                        if (red) "● Recording" else if (b == "NEW") "New" else "$b new",
                        color = if (red) Color.White else ChannelsInk,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(if (red) Jc.Recording else Color(0xFFE8E85A), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (c.progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f)))
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(c.progress)
                            .height(4.dp)
                            .background(Color(0xFFA77BFF)),
                    )
                }
            }
        }
        Text(c.title, color = if (focused) Color.White else Color(0xFFD6CDEE), fontSize = 12.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.sub?.let { Text(it, color = Color(0xFFA99CC8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ChannelsCategory(
    n: LookNav,
    modifier: Modifier,
) {
    Surface(
        onClick = n.go,
        modifier = modifier.width(140.dp).height(58.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF4A2F84), focusedContainerColor = Color(0xFFE4D9FF), contentColor = Color.White, focusedContentColor = ChannelsInk),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(n.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────────── JDVR Estuary ─────────────────────────────

@Composable
private fun EstuaryHome(
    entry: FocusRequester,
    d: LookData,
) {
    val accent = Jc.Focus
    // Kodi starts on the menu's first entry, not its search box (the first thing focus would find).
    LaunchedEffect(Unit) {
        delay(120)
        entry.tryFocus()
    }
    Box(Modifier.fillMaxSize()) {
        // The header: the app at the left, the time and date at the right.
        Row(Modifier.padding(start = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glyph(MenuIcon.Recorded, 20.dp, accent)
            Text("JDVR", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 8.dp), horizontalAlignment = Alignment.End) {
            Text(d.now.time(), color = Color.White, fontSize = 15.sp)
            Text(
                d.now.format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("EEEE, d MMMM yyyy"),
                ),
                color = Color(0xFFB7CAD4),
                fontSize = 10.sp,
            )
        }
        Row(Modifier.fillMaxSize().padding(start = 16.dp, top = 52.dp, bottom = 16.dp)) {
            // The menu panel.
            Column(
                Modifier
                    .width(190.dp)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .padding(vertical = 9.dp),
            ) {
                d.nav.firstOrNull { it.key == "search" }?.let { s -> EstuarySearch(s.go) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    d.nav.filter { it.key != "search" && it.key != "settings" }.forEachIndexed { i, n -> EstuaryMenuItem(n, accent, Modifier.entryIf(i == 0, entry)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    d.nav.firstOrNull { it.key == "series" }?.let { EstuaryFootIcon(MenuIcon.Series, accent, it.go) }
                    d.nav.firstOrNull { it.key == "settings" }?.let { EstuaryFootIcon(MenuIcon.Settings, accent, it.go) }
                    d.nav.firstOrNull { it.key == "search" }?.let { EstuaryFootIcon(MenuIcon.Search, accent, it.go) }
                }
            }
            // The widgets: rows of posters.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (d.rows.isEmpty()) Text(if (d.loaded) "Nothing new yet." else "Loading…", color = Color(0xFFB7CAD4), fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp))
                d.rows.forEach { row ->
                    Text(row.title, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.cards.forEach { c -> EstuaryPoster(c, accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EstuarySearch(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp).padding(bottom = 9.dp).height(28.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f), focusedContainerColor = Jc.Focus, contentColor = Color(0xFFDCE7EC), focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search…", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Glyph(MenuIcon.Search, 14.dp, Color.White)
        }
    }
}

@Composable
private fun EstuaryMenuItem(
    n: LookNav,
    accent: Color,
    modifier: Modifier,
) {
    Surface(
        onClick = n.go,
        modifier = modifier.fillMaxWidth().padding(horizontal = 9.dp).height(if (n.sub != null) 40.dp else 33.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = accent, contentColor = Color.White, focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(n.icon, 15.dp, Color.White)
            Column {
                Text(n.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.sub?.let { Text(it, color = Color(0xFFE6F3F8), fontSize = 9.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun EstuaryFootIcon(
    icon: MenuIcon,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = accent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glyph(icon, 18.dp, Color.White) } }
}

@Composable
private fun EstuaryPoster(
    c: LookCard,
    accent: Color,
) {
    Surface(
        onClick = c.onClick,
        modifier = Modifier.width(99.dp).height(148.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF16485F), focusedContainerColor = Color(0xFF16485F)),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))), focusedBorder = Border(BorderStroke(3.dp, accent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize()) {
            c.image?.let { AsyncImage(model = it, contentDescription = c.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 4f))
                Text(c.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = shadow)
                c.sub?.let { Text(it, color = Color(0xFFE6F3F8), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow) }
            }
            if (c.badgeRed && c.badge != null) {
                Text("● REC", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xFFE0443A)).padding(horizontal = 4.dp, vertical = 1.dp))
            } else if (c.badge != null) {
                Text(c.badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).background(accent).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (c.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(c.progress)
                        .height(3.dp)
                        .background(accent),
                )
            }
        }
    }
}

/**
 * Channels JDVR's icon strip down the left of every screen but Home (which draws its own): the
 * places, the current one in purple. ◄ from the screen's left edge lands here; ◄ again opens the
 * slide-out menu with the names.
 */
@Composable
fun ChannelsSideStrip(
    selected: Screen,
    libraryId: UUID?,
    libraries: List<Library>,
    showRecorded: Boolean,
    onSelect: (Screen) -> Unit,
    onSelectLibrary: (UUID) -> Unit,
) {
    val rights = deviceRights()
    Column(
        Modifier
            .width(58.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(top = 46.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        fun on(s: Screen) = selected == s && s != Screen.Library
        ChannelsStripIcon(MenuIcon.Home, selected = false, onFocus = {}) { onSelect(Screen.Home) }
        if (showRecorded) ChannelsStripIcon(MenuIcon.Recorded, selected = on(Screen.RecordedShows), onFocus = {}) { onSelect(Screen.RecordedShows) }
        if (rights.liveTv) ChannelsStripIcon(MenuIcon.LiveTv, selected = on(Screen.LiveTv), onFocus = {}) { onSelect(Screen.LiveTv) }
        ChannelsStripIcon(MenuIcon.Guide, selected = on(Screen.Guide), onFocus = {}) { onSelect(Screen.Guide) }
        ChannelsStripIcon(MenuIcon.Scheduled, selected = on(Screen.Scheduled), onFocus = {}) { onSelect(Screen.Scheduled) }
        if (rights.record) ChannelsStripIcon(MenuIcon.Series, selected = on(Screen.Series), onFocus = {}) { onSelect(Screen.Series) }
        libraries.inMenuOrder().forEach { lib -> ChannelsStripIcon(lib.menuIcon(), selected = selected == Screen.Library && libraryId == lib.id, onFocus = {}) { onSelectLibrary(lib.id) } }
        ChannelsStripIcon(MenuIcon.Search, selected = on(Screen.Search), onFocus = {}) { onSelect(Screen.Search) }
        ChannelsStripIcon(MenuIcon.Settings, selected = on(Screen.Settings), onFocus = {}) { onSelect(Screen.Settings) }
    }
}
