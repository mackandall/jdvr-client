package com.mackandall.jdvr.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Where Gemstone's Home was left: the menu entry, the row, and the poster in each entry's flow. */
private object GemState {
    var nav by mutableIntStateOf(0)
    var inFlow by mutableStateOf(false)
    val flow = androidx.compose.runtime.mutableStateMapOf<String, Int>()
}

private class GemNav(
    val key: String,
    val label: String,
    val open: () -> Unit,
)

/** A poster in the flow: what it shows, and what OK on it does. */
private class FlowItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val badge: String?,
    val open: () -> Unit,
)

private fun localOf(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

private val Faint = Color(0xFF2C3038)

/**
 * Home in the SageTV Gemstone theme: the menu across the top (◄ ► along it), and under it a flow of
 * posters for the entry (▼ into it, ◄ ► through it, ▲ back to the menu). The poster in the middle
 * puts its fanart behind everything and its details on the left. OK opens.
 */
@Composable
fun GemHome(
    entry: FocusRequester,
    libraries: List<LiveTvRepository.Library>,
    showRecorded: Boolean,
    showTrash: Boolean,
    onOpenScreen: (Screen) -> Unit,
    onOpenGuide: (UUID?) -> Unit,
    onOpenLibrary: (UUID) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenLibraryItem: (UUID, LiveTvRepository.LibItem) -> Unit,
    onPlayChannel: (UUID) -> Unit,
) {
    val prefs = Jdvr.prefs
    val gem = Jc.Focus
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(5_000)
        }
    }
    val libs = libraries.inMenuOrder()
    val navs =
        buildList {
            prefs.menuOrder.filter { it !in prefs.hiddenMenu && it != "settings" }.forEach { k ->
                when (k) {
                    "livetv" -> add(GemNav(k, "Live TV") { onOpenScreen(Screen.LiveTv) })
                    "guide" -> add(GemNav(k, "Guide") { onOpenGuide(null) })
                    "recorded" -> if (showRecorded) add(GemNav(k, "Recordings") { onOpenScreen(Screen.RecordedShows) })
                    "libraries" -> libs.forEach { lib -> add(GemNav("lib:${lib.id}", lib.name) { onOpenLibrary(lib.id) }) }
                    "scheduled" -> add(GemNav(k, "Scheduled") { onOpenScreen(Screen.Scheduled) })
                    "series" -> add(GemNav(k, "Series") { onOpenScreen(Screen.Series) })
                    "trash" -> if (showTrash) add(GemNav(k, "Trash") { onOpenScreen(Screen.Trash) })
                    "search" -> add(GemNav(k, "Search") { onOpenScreen(Screen.Search) })
                    "settings" -> add(GemNav(k, "Settings") { onOpenScreen(Screen.Settings) })
                }
            }
            // Settings comes last and can't be hidden.
            add(GemNav("settings", "Settings") { onOpenScreen(Screen.Settings) })
        }
    if (navs.isEmpty()) return
    val navIndex = GemState.nav.coerceIn(0, navs.lastIndex)
    val nav = navs[navIndex]

    // Each entry's posters, loaded when it's first reached and kept while Home is open.
    val flows = remember { mutableStateMapOf<String, List<FlowItem>>() }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Jdvr.liveTv.channels() }.getOrNull()?.let { channels = it }
            delay(60_000)
        }
    }
    val chs = channels
    LaunchedEffect(nav.key, chs) {
        val k = nav.key
        val items: List<FlowItem>? =
            when {
                k == "livetv" || k == "guide" ->
                    chs?.filter { it.now != null }?.map { c ->
                        val p = c.now!!
                        FlowItem(
                            id = c.id.toString(),
                            title = p.name,
                            subtitle = listOfNotNull("${c.number} ${c.name}", "until ${p.end.time()}").joinToString(" · "),
                            overview = p.overview,
                            poster = Jdvr.liveTv.imageUrl(p.id, 400),
                            backdrop = Jdvr.liveTv.imageUrl(p.id, 1080),
                            badge = if (p.isRecording) "● Recording" else null,
                            open = { if (k == "livetv") onPlayChannel(c.id) else onOpenGuide(c.id) },
                        )
                    }
                k == "recorded" ->
                    Jdvr.liveTv.recordedShows(prefs.recordingsFilter)?.map { r ->
                        FlowItem(
                            id = r.id,
                            title = r.name,
                            subtitle = r.subtitle ?: (if (r.count > 1) "${r.count} recordings" else null),
                            overview = null,
                            poster = Jdvr.liveTv.imageUrl(r.imageId ?: r.id, 400),
                            backdrop = r.id.toUuid()?.let { Jdvr.liveTv.backdropUrl(it) },
                            badge = if (r.unplayedCount > 0) (if (r.count > 1) "${r.unplayedCount} new" else "New") else null,
                            open = { onOpenShow(r.id) },
                        )
                    }
                k.startsWith("lib:") -> {
                    val libId = k.removePrefix("lib:").toUuid() ?: return@LaunchedEffect
                    Jdvr.liveTv.latestIn(libId, 20)?.map { item ->
                        val art = item.seriesId ?: item.id
                        FlowItem(
                            id = item.id.toString(),
                            title = item.seriesName ?: item.name,
                            subtitle = listOfNotNull(item.seasonEpisode, item.name.takeIf { item.seriesName != null }, item.year?.toString()).joinToString(" · ").ifEmpty { null },
                            overview = item.overview,
                            poster = Jdvr.liveTv.imageUrl(art, 400),
                            backdrop = Jdvr.liveTv.backdropUrl(art),
                            badge = item.unplayed?.takeIf { it > 0 }?.let { "$it new" },
                            open = { onOpenLibraryItem(libId, item) },
                        )
                    }
                }
                k == "scheduled" ->
                    Jdvr.liveTv.schedule()?.map { u ->
                        val pid = u.programId?.toUuid()
                        val start = localOf(u.start)
                        FlowItem(
                            id = u.timerId,
                            title = u.name,
                            subtitle = listOfNotNull(u.episodeTitle, start?.let { (if (it.toLocalDate() == LocalDateTime.now().toLocalDate()) "" else it.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } + " ") + it.time() }, listOfNotNull(u.channelNumber, u.channelName).joinToString(" ").ifEmpty { null }).joinToString(" · "),
                            overview = null,
                            poster = pid?.let { Jdvr.liveTv.imageUrl(it, 400) },
                            backdrop = pid?.let { Jdvr.liveTv.imageUrl(it, 1080) },
                            badge = if (u.recordingNow) "● Recording" else null,
                            open = { onOpenScreen(Screen.Scheduled) },
                        )
                    }
                else -> emptyList()
            }
        if (items != null) flows[k] = items
    }
    val flow = flows[nav.key].orEmpty()
    val flowIndex = (GemState.flow[nav.key] ?: 0).coerceIn(0, (flow.size - 1).coerceAtLeast(0))
    val inFlow = GemState.inFlow && flow.isNotEmpty()
    val shown = flow.getOrNull(flowIndex)

    val navList = rememberLazyListState()
    LaunchedEffect(navIndex) {
        val visible = navList.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == navIndex } || visible.lastOrNull()?.index == navIndex || visible.firstOrNull()?.index == navIndex) {
            navList.animateScrollToItem((navIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C0F))
            .focusRequester(entry)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        if (inFlow) GemState.flow[nav.key] = (flowIndex - 1).coerceAtLeast(0) else GemState.nav = (navIndex - 1 + navs.size) % navs.size
                        true
                    }
                    Key.DirectionRight -> {
                        if (inFlow) GemState.flow[nav.key] = (flowIndex + 1).coerceAtMost(flow.lastIndex) else GemState.nav = (navIndex + 1) % navs.size
                        true
                    }
                    Key.DirectionDown -> {
                        if (flow.isNotEmpty()) GemState.inFlow = true
                        true
                    }
                    Key.DirectionUp -> {
                        GemState.inFlow = false
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            if (inFlow) shown?.open?.invoke() else nav.open()
                        }
                        true
                    }
                    else -> false
                }
            }.focusable(),
    ) {
        // The fanart of the poster in the middle, behind black glass.
        Crossfade(targetState = shown?.backdrop, animationSpec = tween(600), label = "gem fanart") { url ->
            if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEB08090C), Color(0x8C08090C), Color(0x2608090C)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 0.75f to Color(0xE608090C), 1f to Color(0xFF08090C))))

        // The menu across the top.
        Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 42.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JDVR", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp, modifier = Modifier.padding(end = 24.dp))
            LazyRow(state = navList, modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(end = 24.dp)) {
                itemsIndexed(navs, key = { _, n -> n.key }) { i, n ->
                    val on = i == navIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            n.label.uppercase(),
                            color = if (on) Color.White else Color(0xFF9AA0AA),
                            fontSize = 17.sp,
                            letterSpacing = 2.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            style = if (on && !inFlow) TextStyle(shadow = Shadow(gem, blurRadius = 16f)) else TextStyle.Default,
                            maxLines = 1,
                        )
                        Box(Modifier.padding(top = 4.dp).height(2.dp).width(if (on) 40.dp else 0.dp).background(if (on) gem.copy(alpha = if (inFlow) 0.5f else 1f) else Color.Transparent))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(now.time().substringBefore(" "), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
                Text(now.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d")).uppercase(), color = Color(0xFF9AA0AA), fontSize = 11.sp, letterSpacing = 1.sp)
            }
        }

        // The poster's details.
        Column(Modifier.padding(start = 42.dp, top = 88.dp).width(470.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (shown != null) {
                Text(nav.label.uppercase(), color = gem, fontSize = 12.sp, letterSpacing = 3.sp)
                Text(shown.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light, lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                shown.subtitle?.let { Text(it, color = Color(0xFFD5D8DD), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                shown.badge?.let {
                    Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.border(1.dp, gem, RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                shown.overview?.let { Text(it, color = Color(0xFFC3C7CE), fontSize = 14.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            } else if (flows[nav.key] == null && flowKinds(nav.key)) {
                Text("Loading…", color = Color(0xFF9AA0AA), fontSize = 16.sp)
            } else {
                Text(nav.label, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
                Text(if (flowKinds(nav.key)) "Nothing here yet." else "OK to open.", color = Color(0xFF9AA0AA), fontSize = 16.sp)
            }
        }

        // The flow: the poster in the middle large and lit, the others smaller and fainter.
        if (flow.isNotEmpty()) {
            val pos by animateFloatAsState(flowIndex.toFloat(), tween(260), label = "flow")
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val centreX = maxWidth / 2
                val baseY = maxHeight - 70.dp
                flow.forEachIndexed { i, item ->
                    val d = i - pos
                    val a = kotlin.math.abs(d)
                    if (a > 4.5f) return@forEachIndexed
                    val w = posterWidth(a)
                    val h = w * 1.5f
                    val x = centreX + offsetFor(d) - w / 2
                    val focusedOne = i == flowIndex
                    Column(
                        Modifier
                            .offset(x = x, y = baseY - h)
                            .width(w)
                            .graphicsLayer { alpha = (1f - a * 0.22f).coerceIn(0.25f, 1f) },
                    ) {
                        Box(
                            Modifier
                                .width(w)
                                .height(h)
                                .then(if (focusedOne && inFlow) Modifier.shadow(18.dp, RoundedCornerShape(4.dp), ambientColor = gem, spotColor = gem) else Modifier)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1A1D24))
                                .border(if (focusedOne && inFlow) 2.dp else 1.dp, if (focusedOne && inFlow) gem else Faint, RoundedCornerShape(4.dp)),
                        ) {
                            if (item.poster != null) AsyncImage(model = item.poster, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (item.poster == null) Text(item.title, color = Color(0xFF9AA0AA), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(6.dp))
                            item.badge?.takeIf { a < 0.5f }?.let {
                                Text(it, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(2.dp)).border(1.dp, gem, RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                        // Its reflection on the floor.
                        if (item.poster != null) {
                            AsyncImage(
                                model = item.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.BottomCenter,
                                modifier =
                                    Modifier
                                        .padding(top = 3.dp)
                                        .width(w)
                                        .height(h * 0.22f)
                                        .graphicsLayer {
                                            scaleY = -1f
                                            alpha = 0.18f
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }.drawWithContent {
                                            drawContent()
                                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), blendMode = BlendMode.DstIn)
                                        },
                            )
                        }
                    }
                }
                shown?.let {
                    Text(
                        it.title.uppercase() + (it.badge?.let { b -> " · ${b.uppercase()}" } ?: ""),
                        color = Color(0xFFD5D8DD),
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 120.dp, end = 120.dp),
                    )
                }
            }
        }
    }
}

/** Entries that have a flow of posters (the rest just open). */
private fun flowKinds(key: String) = key in setOf("livetv", "guide", "recorded", "scheduled") || key.startsWith("lib:")

private fun posterWidth(a: Float): Dp = if (a <= 1f) lerp(112.dp, 88.dp, a) else lerp(88.dp, 72.dp, (a - 1f).coerceAtMost(1f))

/** How far from the middle a poster sits ([d] posters away, fractions while sliding). */
private fun offsetFor(d: Float): Dp {
    val a = kotlin.math.abs(d)
    val dist = if (a <= 1f) 118.dp * a else 118.dp + 96.dp * (a - 1f)
    return if (d < 0) -dist else dist
}
