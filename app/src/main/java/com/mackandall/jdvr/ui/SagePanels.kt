package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.MarkSymbols
import com.mackandall.jdvr.data.RecordingCard
import com.mackandall.jdvr.data.Upcoming
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** The same menu drawn in Gemstone's look: fanart behind black glass, one gem colour. */
internal val LocalGemLook = androidx.compose.runtime.compositionLocalOf { false }
internal val SageBlue = Color(0xFF4F86D8)
internal val SageBox = Color(0xFF06122F).copy(alpha = 0.55f)
internal val SageLabel = Color(0xFF8FB4E8)
internal val GemLine = Color(0xFF2C3038)
internal val GemBox = Color(0xFF08090C).copy(alpha = 0.6f)
internal val GemGrey = Color(0xFF9AA0AA)

@Composable
internal fun panelBorder() = if (LocalGemLook.current) GemLine else SageBlue

@Composable
internal fun panelFill() = if (LocalGemLook.current) GemBox else SageBox

@Composable
internal fun panelLabel() = if (LocalGemLook.current) Jc.Focus else SageLabel

private fun localOf(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

@Composable
internal fun SagePanel(
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
internal fun PanelLine(
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
internal fun LivePanel(
    c: Channel,
    now: LocalDateTime,
    label: String,
    symbols: MarkSymbols?,
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
            val image = p?.let { Jdvr.backend.imageUrl(it.id, 360) } ?: c.logoUrl
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
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(if (LocalGemLook.current) 3.dp else 5.dp)
                        .background(if (LocalGemLook.current) GemLine else Color(0xFF1B3566), RoundedCornerShape(3.dp)),
                ) {
                    Box(Modifier.fillMaxWidth(done.toFloat() / total).height(if (LocalGemLook.current) 3.dp else 5.dp).background(if (LocalGemLook.current) Jc.Focus else Color(0xFF5FB0FF), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

@Composable
internal fun NextPanel(
    // Null until the first answer: "Nothing scheduled" only once that's known.
    upcoming: List<Upcoming>?,
    now: LocalDateTime,
    count: Int,
) {
    val next = upcoming.orEmpty().filter { !it.recordingNow }.take(count)
    SagePanel("RECORDING NEXT") {
        if (upcoming == null) {
            Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
        } else if (next.isEmpty()) {
            Text("Nothing scheduled.", color = Jc.Muted, fontSize = 14.sp)
        }
        next.forEach { u ->
            val start = localOf(u.start)
            val time =
                start?.let {
                    if (it.toLocalDate() == now.toLocalDate()) {
                        it.time()
                    } else {
                        it.dayOfWeek.name
                            .take(3)
                            .lowercase()
                            .replaceFirstChar { c -> c.uppercase() } + " " + it.time()
                    }
                } ?: ""
            PanelLine("● " + u.name + (u.episodeTitle?.let { " · $it" } ?: ""), time)
        }
    }
}

/** Gemstone's Recorded Shows panel: the newest recordings' posters. */
@Composable
internal fun NewRecordingsPanel(cards: List<RecordingCard>) {
    SagePanel("NEW RECORDINGS") {
        val fresh = cards.filter { it.unplayedCount > 0 }.ifEmpty { cards }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fresh.take(3).forEach { c ->
                AsyncImage(
                    model = Jdvr.backend.imageUrl(c.imageId ?: c.id, 240),
                    contentDescription = c.name,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .width(90.dp)
                            .height(135.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF14161B)),
                )
            }
        }
        fresh.take(3).forEach { PanelLine(it.name, if (it.unplayedCount > 1) "${it.unplayedCount} new" else "") }
    }
}

@Composable
internal fun LibraryPanel(
    lib: Library,
    items: List<LibItem>?,
) {
    SagePanel("RECENTLY ADDED · ${lib.name.uppercase()}") {
        val list = items
        if (list == null) {
            Text("Loading…", color = Jc.Muted, fontSize = 14.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.take(3).forEach { it ->
                    AsyncImage(
                        model = Jdvr.backend.imageUrl(it.seriesId ?: it.id, 240),
                        contentDescription = it.seriesName ?: it.name,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(90.dp)
                                .height(135.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(panelFill()),
                    )
                }
            }
            list.forEach { PanelLine(it.seriesName ?: it.name, it.seasonEpisode ?: it.year?.toString() ?: "") }
        }
    }
}

/** SageTV's channel list for "Jump to a channel": OK opens the guide there. */
@Composable
internal fun ChannelPicker(
    channels: List<Channel>,
    current: UUID?,
    onPick: (UUID) -> Unit,
) {
    val first = remember { FocusRequester() }
    val gem = Jc.Style == UiStyle.Gem
    val start = channels.indexOfFirst { it.id == current }.coerceAtLeast(0)
    val state =
        androidx.compose.foundation.lazy
            .rememberLazyListState((start - 3).coerceAtLeast(0))
    LaunchedEffect(channels.isNotEmpty()) {
        first.focusSoon(withinMs = 1500)
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
                            (
                                if (focused && gem) {
                                    Modifier.background(Jc.Focus.copy(alpha = 0.18f))
                                } else if (focused) {
                                    Modifier.sageGloss()
                                } else {
                                    Modifier
                                }
                            ).fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
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
