package com.mackandall.jdvr.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.DateChannel
import com.mackandall.jdvr.data.DateRecording
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun localTime(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

private val WeekdayFormat = DateTimeFormatter.ofPattern("EEEE")
private val MonthDayFormat = DateTimeFormatter.ofPattern("MMMM d")
private val OlderFormat = DateTimeFormatter.ofPattern("EEE, MMM d")
private val OlderYearFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")

/** "Today", "Yesterday", "Last Monday" within the week, else the date; and the date beside the first three. */
private fun dayHeading(at: LocalDateTime?): Pair<String, String?> {
    if (at == null) return "Earlier" to null
    val day = at.toLocalDate()
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0 -> "Today" to day.format(MonthDayFormat)
        days == 1L -> "Yesterday" to day.format(MonthDayFormat)
        days < 7 -> "Last " + day.format(WeekdayFormat) to day.format(MonthDayFormat)
        day.year == today.year -> day.format(OlderFormat) to null
        else -> day.format(OlderYearFormat) to null
    }
}

private sealed interface DateLine {
    data class Day(
        val label: String,
        val date: String?,
    ) : DateLine

    data class Item(
        val r: DateRecording,
        val at: LocalDateTime?,
    ) : DateLine
}

/**
 * Recorded Shows as a list by date (SageTV's and Channels DVR's recent recordings): one line per
 * recording, newest first, grouped by day, each with the logo and number of the channel it came
 * from, so a recording is spotted by its channel while scrolling. The focused one's details sit
 * beside the list. OK plays; Hold OK (or Menu) opens its quick actions. Where the logo goes,
 * start or end of the line, is Episode History's setting.
 */
@Composable
fun RecordingsByDate(
    filter: String,
    reload: Int,
    entry: FocusRequester,
    onPlay: (UUID) -> Unit,
    onHold: (UUID) -> Unit,
    onDetails: (UUID) -> Unit,
    onUnavailable: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<DateLine>>(emptyList()) }
    var logoRight by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<DateRecording?>(null) }
    LaunchedEffect(filter, reload) {
        val data = Jdvr.backend.recordingsByDate(filter)
        if (data == null) {
            onUnavailable()
            return@LaunchedEffect
        }
        logoRight = data.dateLogo == "right"
        val result = mutableListOf<DateLine>()
        var day: String? = null
        data.recordings.forEach { r ->
            val at = localTime(r.recordedUtc)
            val (label, date) = if (r.recording) "Recording now" to null else dayHeading(at)
            if (label != day) {
                day = label
                result += DateLine.Day(label, date)
            }
            result += DateLine.Item(r, at)
        }
        lines = result
        focused = focused?.let { f -> data.recordings.firstOrNull { it.id == f.id } } ?: data.recordings.firstOrNull()
        loaded = true
    }

    if (!loaded) {
        Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
        return
    }
    if (lines.isEmpty()) {
        Text("Nothing recorded yet.", color = Jc.Muted, fontSize = 16.sp)
        return
    }

    // Focus the first line the first time the list is there (the rail hands focus over with [entry]).
    LaunchedEffect(Unit) {
        entry.focusSoon(withinMs = 2000)
    }

    val firstItem = lines.indexOfFirst { it is DateLine.Item }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp, start = 6.dp, end = 6.dp),
        ) {
            itemsIndexed(lines, key = { _, l -> if (l is DateLine.Item) l.r.id else "day:" + (l as DateLine.Day).label }) { i, line ->
                when (line) {
                    is DateLine.Day -> {
                        Row(Modifier.padding(top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Text(line.label, color = Jc.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            line.date?.let { Text("  $it", color = Jc.Muted, fontSize = 15.sp) }
                        }
                    }

                    is DateLine.Item -> {
                        DateRow(
                            line.r,
                            line.at,
                            logoRight,
                            if (i == firstItem) Modifier.focusRequester(entry) else Modifier,
                            onFocus = { focused = line.r },
                            onClick = {
                                line.r.id
                                    .toUuid()
                                    ?.let(onPlay)
                            },
                            onHold = {
                                line.r.id
                                    .toUuid()
                                    ?.let(onHold)
                            },
                            onDetails = {
                                line.r.id
                                    .toUuid()
                                    ?.let(onDetails)
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(0.42f).fillMaxHeight()) {
            focused?.let { DateDetails(it) }
        }
    }
}

@Composable
private fun DateRow(
    r: DateRecording,
    at: LocalDateTime?,
    logoRight: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onHold: () -> Unit,
    onDetails: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    // FocusFill is a panel colour (white in the light theme): the usual text colours stay readable on it.
    val text = Jc.Text
    val muted = Jc.Muted
    val held = { holdOk(menu = onHold, details = onDetails) }
    val hold = if (holdEnabled) held else null
    Surface(
        onClick = onClick,
        onLongClick = hold,
        modifier =
            modifier
                .fillMaxWidth()
                .height(84.dp)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                }.then(if (holdEnabled) Modifier.onMenuKey(held) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WatchedDot(r)
            if (!logoRight) ChannelTile(r.channel, numberInside = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.title, color = text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The state chips go on the second line, so a long one ("Stalled · 98 min lost") can't squeeze the title.
                val sub =
                    listOfNotNull(
                        if (r.type == "Episode") r.episodeTitle else r.year?.toString(),
                        numbers(r),
                        r.minutes?.let { "$it min" },
                        if (logoRight) at?.time() else null,
                    ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChips(r)
                    if (sub.isNotEmpty()) Text(sub, color = muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (logoRight) {
                ChannelTile(r.channel, numberInside = false, numberColor = muted)
            } else {
                Text(at?.time() ?: "", color = muted, fontSize = 15.sp)
            }
        }
    }
}

private fun numbers(r: DateRecording): String? = if (r.season != null && r.episode != null) "S${r.season} E${r.episode}" else null

@Composable
private fun WatchedDot(r: DateRecording) {
    val m = Modifier.size(12.dp).padding(0.dp)
    when {
        r.played -> Box(m.border(2.dp, Jc.Muted, CircleShape))
        r.playedPercent > 0 -> Box(m.border(2.dp, Jc.Focus, CircleShape).padding(3.dp).background(Jc.Focus, CircleShape))
        else -> Box(m.background(Jc.Focus, CircleShape))
    }
}

/**
 * The channel's logo, scaled to fit a fixed tile (wide and square logos take the same room, so
 * titles line up), with its number; the channel's name when it has no logo; an empty tile when
 * the channel isn't known.
 */
@Composable
internal fun ChannelTile(
    ch: DateChannel?,
    numberInside: Boolean,
    numberColor: Color = Jc.Muted,
    width: Int = 116,
    height: Int = 64,
) {
    Column(Modifier.width(width.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(
            Modifier
                .width(width.dp)
                .height(height.dp)
                .background(Jc.Panel2, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (ch != null) {
                    val logo = if (ch.hasLogo) Jdvr.backend.imageUrl(ch.id, 120) else null
                    if (logo != null) {
                        AsyncImage(model = logo, contentDescription = ch.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(ch.name, color = Jc.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (numberInside && ch?.number != null) Text(ch.number, color = Jc.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!numberInside && ch?.number != null) Text(ch.number, color = numberColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusChips(r: DateRecording) {
    if (r.recording) Chip("● Recording", Jc.Recording)
    val warn = r.statusShort ?: if (r.partial) "Partial" + (r.minutes?.let { " · $it min" } ?: "") else null
    warn?.let { Chip(it, Jc.Amber, dark = true) }
    if (r.archived) Chip("Archived", Jc.Panel2, textColor = Jc.Text)
}

@Composable
private fun Chip(
    text: String,
    color: Color,
    dark: Boolean = false,
    textColor: Color? = null,
) {
    Text(
        text,
        color = textColor ?: badgeText(color, dark),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * The focused recording: picture, titles, channel, state, and the guide's description. Everything
 * fits the pane: the picture gives up room first, and a description longer than five lines or so
 * scrolls slowly by itself (the pane can't take focus, so the remote can't scroll it).
 */
@Composable
private fun DateDetails(r: DateRecording) {
    val at = localTime(r.recordedUtc)
    Column(Modifier.fillMaxSize().padding(top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val image = (r.imageId ?: r.id).let { Jdvr.backend.imageUrl(it, 360) }
        Box(Modifier.weight(1f, fill = false).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).background(Jc.Panel2, RoundedCornerShape(12.dp))) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Jc.Panel2, RoundedCornerShape(12.dp)))
            }
        }
        Text(r.title, color = Jc.Title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        r.episodeTitle?.let { Text(it, color = Jc.Text, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            r.channel?.let { ChannelTile(it, numberInside = false, width = 84, height = 46) }
            val line =
                listOfNotNull(
                    if (r.season != null && r.episode != null) "S${r.season} E${r.episode}" else r.year?.toString(),
                    r.channel?.let { listOfNotNull(it.number, it.name).joinToString(" ") },
                    at?.let { dayHeading(it).first + " " + it.time() },
                    r.minutes?.let { "$it min" },
                ).joinToString(" · ")
            Text(line, color = Jc.Muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(
                if (r.played) {
                    "Watched"
                } else if (r.playedPercent > 0) {
                    "${r.playedPercent}% watched"
                } else {
                    "Not watched"
                },
                Jc.Panel2,
                textColor = Jc.Text,
            )
            if (r.archived) Chip("Archived", Jc.Panel2, textColor = Jc.Text)
        }
        when {
            r.recording -> {
                Text("Recording now", color = Jc.Recording, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            r.statusText != null || r.partial -> {
                Column(
                    Modifier.fillMaxWidth().background(Jc.Amber.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(r.statusShort ?: "Partial recording", color = Jc.Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(r.statusText ?: "Too short to count as recorded. Hold OK to keep it or record it again.", color = Jc.Text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        r.overview?.let { ScrollingText(it, r.id, Modifier.heightIn(max = 130.dp)) }
        Text("OK Play  ·  Hold OK Options", color = Jc.Muted, fontSize = 14.sp)
    }
}

/** Text in the room it's given; when it doesn't fit, it waits, scrolls slowly to its end, waits, and starts over. */
@Composable
private fun ScrollingText(
    text: String,
    key: Any,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(key, text) {
        scroll.scrollTo(0)
        while (true) {
            delay(3_000)
            val max = scroll.maxValue
            if (max <= 0) continue
            scroll.animateScrollTo(max, tween(durationMillis = max * 40, easing = LinearEasing))
            delay(3_000)
            scroll.animateScrollTo(0, tween(600))
        }
    }
    Text(text, color = Jc.Text, fontSize = 15.sp, modifier = modifier.fillMaxWidth().verticalScroll(scroll, enabled = false))
}
