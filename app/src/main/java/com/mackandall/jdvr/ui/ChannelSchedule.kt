package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.mackandall.jdvr.data.MarkSymbols
import com.mackandall.jdvr.data.Program
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

// As far ahead as guides go (Schedules Direct: about two weeks).
private const val SCHEDULE_DAYS = 14L

/** One line of the schedule: a day's heading, or a programme. */
private sealed interface ScheduleLine {
    data class Day(
        val date: LocalDate,
    ) : ScheduleLine

    data class Show(
        val program: Program,
    ) : ScheduleLine
}

private fun dayHeading(d: LocalDate): String {
    val name = d.atStartOfDay().day()
    return when (ChronoUnit.DAYS.between(LocalDate.now(), d)) {
        0L -> "Today · $name"
        1L -> "Tomorrow · $name"
        else -> name
    }
}

/**
 * One channel's programmes, down the screen by day (the web client's channel page): opened from
 * the channel's logo at the left of its guide row. OK on a programme opens the guide's actions for
 * it ([onSelect]); Back goes to the guide ([onClose]).
 */
@Composable
internal fun ChannelSchedule(
    channel: Channel,
    symbols: MarkSymbols?,
    // Bumped by the guide after an action, so recording dots follow.
    reload: Int,
    entry: FocusRequester,
    // The programme to come back to (after the player or the menu), else what's on now.
    home: UUID?,
    actionsOpen: Boolean,
    onFocused: (Program) -> Unit,
    onSelect: (Program) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = !actionsOpen) { onClose() }
    var programs by remember(channel.id) { mutableStateOf<List<Program>?>(null) }
    var error by remember(channel.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.id, reload) {
        val now = LocalDateTime.now()
        try {
            programs =
                Jdvr.backend
                    .programs(listOf(channel.id), now, now.plusDays(SCHEDULE_DAYS))
                    .filter { it.channelId == channel.id && it.end.isAfter(now) }
                    .distinctBy { it.id }
                    .sortedBy { it.start }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Loading the channel's schedule failed")
            error = "Couldn't load the schedule: ${e.message}"
        }
    }
    val lines =
        remember(programs) {
            buildList {
                var day: LocalDate? = null
                programs.orEmpty().forEach { p ->
                    val d = p.start.toLocalDate()
                    if (d != day) add(ScheduleLine.Day(d)).also { day = d }
                    add(ScheduleLine.Show(p))
                }
            }
        }
    val listState = rememberLazyListState()
    // Opening: what's on now takes focus (its row, the first, is on screen).
    // The way in (from the guide, the player or the menu) is the row last focused, else the first.
    val target = programs?.let { ps -> ps.firstOrNull { it.id == home } ?: ps.firstOrNull() }
    LaunchedEffect(programs != null) {
        val t = target ?: return@LaunchedEffect
        val index = lines.indexOfFirst { it is ScheduleLine.Show && it.program.id == t.id }
        if (index > 1) listState.scrollToItem(index - 1)
        entry.focusSoon()
    }

    Column(modifier.padding(start = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(120.dp).height(64.dp).background(Jc.Panel2, RoundedCornerShape(10.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                if (channel.logoUrl != null) {
                    AsyncImage(model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(channel.name, color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column {
                Text("${channel.number} ${channel.name}", color = Jc.Title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Coming up on this channel · Back: the guide", color = Jc.Muted, fontSize = 15.sp)
            }
        }
        error?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
        if (programs == null) {
            if (error == null) Text("Loading the schedule…", color = Jc.Muted, fontSize = 16.sp)
            return@Column
        }
        if (lines.isEmpty()) {
            Text("No guide data for this channel.", color = Jc.Muted, fontSize = 16.sp)
            return@Column
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(lines, key = {
                when (it) {
                    is ScheduleLine.Day -> "d" + it.date
                    is ScheduleLine.Show -> it.program.id.toString()
                }
            }) { line ->
                when (line) {
                    is ScheduleLine.Day ->
                        Text(
                            dayHeading(line.date),
                            color = Jc.Title,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp),
                        )

                    is ScheduleLine.Show ->
                        ScheduleRow(
                            p = line.program,
                            symbols = symbols,
                            entry = if (line.program.id == target?.id) entry else null,
                            onFocused = { onFocused(line.program) },
                            onClick = { onSelect(line.program) },
                        )
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    p: Program,
    symbols: MarkSymbols?,
    entry: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    // Registered with the guide's cells, so closing the actions panel refocuses this row.
    val focus = remember(p.id) { FocusRequester() }
    val registry = LocalCellFocus.current
    DisposableEffect(p.id) {
        registry[p.id] = focus
        onDispose { if (registry[p.id] === focus) registry.remove(p.id) }
    }
    val extras = LocalGuideExtras.current
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    val npvr = Jc.Style == UiStyle.NextPvr
    val sage = Jc.Style == UiStyle.Sage
    var rowFocused by remember { mutableStateOf(false) }
    val records = extras.realRecording(p)
    val darkType = npvr && rowFocused && !records
    val titleColor = if (darkType) NextPvrInk else Jc.Text
    val subColor = if (darkType) NextPvrInk.copy(alpha = 0.75f) else Jc.Muted
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .focusRequester(focus)
                .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
                .onFocusChangedCompat {
                    rowFocused = it
                    if (it) onFocused()
                },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor =
                    when {
                        sage && extras.isFavourite(p) -> Jc.Green.copy(alpha = 0.55f)
                        npvr && records -> androidx.compose.ui.graphics.Color(0xFF8F0A00)
                        records -> Jc.Recording.copy(alpha = 0.14f)
                        else -> guidePanel()
                    },
                focusedContainerColor = if (npvr) (if (records) androidx.compose.ui.graphics.Color(0xFFD60E00) else Jc.Green) else Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = titleColor,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = if (npvr) Border.None else Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            (if (sage && rowFocused) Modifier.sageGloss() else Modifier).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.width(96.dp)) {
                Text(p.start.time(), color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                if (p.isOn()) Text("on now", color = if (darkType) subColor else Jc.Focus, fontSize = 12.sp)
            }
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                if (p.isRecording) {
                    if (p.seriesTimerId != null && !extras.isBuffer(p)) {
                        SeriesDots(height = 8.dp)
                    } else {
                        Box(Modifier.size(9.dp).background(Jc.Recording.copy(alpha = if (extras.isBuffer(p)) 0.35f else 1f), CircleShape))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (extras.scanning(p)) Text("CS", color = Jc.OnAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Jc.Amber, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                }
                val sub = listOfNotNull(p.seasonEpisode, marks.title?.takeIf { it.isNotBlank() } ?: if (p.isMovie) "Movie" else null).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (marks.skipped) Text("■", color = Jc.Amber, fontSize = 12.sp)
                    if (marks.otherAiring) Text("↪", color = Jc.Focus, fontSize = 12.sp)
                    if (marks.inLibrary) Text("●", color = Jc.Green, fontSize = 12.sp)
                    if (marks.watched) Text("✓", color = Jc.Green, fontSize = 12.sp)
                    if (sub.isNotEmpty()) Text(sub, color = subColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val minutes =
                java.time.Duration
                    .between(p.start, p.end)
                    .toMinutes()
            Text(
                listOfNotNull(if (p.isRepeat) "repeat" else null, "$minutes min").joinToString(" · "),
                color = subColor,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
