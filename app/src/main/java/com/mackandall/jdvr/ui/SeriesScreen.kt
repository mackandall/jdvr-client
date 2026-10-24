package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Series recordings (Jellyfin's, tied to the guide's series id) beside Recording Favourites
 * (JellyDVR Schedule's, by title): see and manage both while testing. Removing asks twice.
 */
@Composable
fun SeriesScreen(entry: FocusRequester) {
    var series by remember { mutableStateOf<List<SeriesTimerInfoDto>?>(null) }
    var favourites by remember { mutableStateOf<LiveTvRepository.FavouritesList?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf(false) }
    // A series recording being edited (its options), in place of the lists.
    var editing by remember { mutableStateOf<SeriesTimerInfoDto?>(null) }
    val scope = rememberCoroutineScope()

    // Recording Favourites answered (or didn't: the JellyDVR Schedule plugin isn't on this server).
    var favouritesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        series = runCatching { Jdvr.liveTv.seriesTimers() }.getOrNull()
        favourites = Jdvr.liveTv.favourites()
        favouritesLoaded = true
    }

    fun act(block: suspend () -> String?) {
        scope.launch {
            note = runCatching { block() }.getOrElse {
                android.util.Log.w("JDVR", "series action failed", it)
                "Didn't work: ${it.message ?: it.javaClass.simpleName}"
            }
            delay(1500) // the server schedules in the background
            reload++
        }
    }

    val seriesList = androidx.compose.foundation.lazy.rememberLazyListState()
    // NextPVR: a place up or down its priority list; the list comes back in the new order (the
    // rows are keyed, so focus stays on the moved series' button).
    fun move(
        s: SeriesTimerInfoDto,
        higher: Boolean,
    ) {
        scope.launch {
            note = runCatching { Jdvr.nextPvrBackend?.moveSeries(s.id!!, higher); null }.getOrElse { "Didn't work: ${it.message ?: it.javaClass.simpleName}" }
            series = runCatching { Jdvr.liveTv.seriesTimers() }.getOrNull() ?: series
            // Keep the moved series on screen (the top row can scroll off above).
            series?.indexOfFirst { it.id == s.id }?.takeIf { it >= 0 }?.let { i ->
                if (seriesList.layoutInfo.visibleItemsInfo.none { it.index == i && it.offset >= 0 }) seriesList.animateScrollToItem(i)
            }
        }
    }

    // NextPVR: its own series options, over the list.
    var nextPvrEditing by remember { mutableStateOf<String?>(null) }
    nextPvrEditing?.let { id ->
        NextPvrSeriesOptions(
            onDone = { msg ->
                nextPvrEditing = null
                if (msg != null) act { msg }
            },
            ruleId = id,
        )
    }

    editing?.let { t ->
        androidx.activity.compose.BackHandler { editing = null }
        SeriesEditor(
            timer = t,
            onSave = { changed ->
                editing = null
                act {
                    Jdvr.liveTv.updateSeriesTimer(changed)
                    "Saved: ${changed.name}"
                }
            },
            onCancel = { editing = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Series", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Spacer(12)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Jellyfin series recordings.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Series recordings", color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (Jdvr.isNextPvr) "NextPVR's recurring recordings · when tuners clash, the higher one records" else "Jellyfin's: tied to the guide's id for the show", color = Jc.Muted, fontSize = 13.sp)
                Spacer(8)
                val list = series
                when {
                    list == null -> Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                    list.isEmpty() -> Text("No series recordings.", color = Jc.Muted, fontSize = 15.sp)
                    else ->
                        LazyColumn(state = seriesList, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                            itemsIndexed(list, key = { _, it -> it.id ?: it.name ?: "" }) { i, s ->
                                SeriesRow(
                                    s,
                                    if (s == list.first()) Modifier.focusRequester(entry) else Modifier,
                                    onEdit = { if (Jdvr.isNextPvr) nextPvrEditing = s.id else editing = s },
                                    // NextPVR: its priority order, moved a place at a time.
                                    rank = if (Jdvr.isNextPvr) i + 1 else null,
                                    onMove = if (Jdvr.isNextPvr) ({ higher -> move(s, higher) }) else null,
                                ) {
                                    act {
                                        Jdvr.liveTv.cancelSeries(s.id!!)
                                        "Series recording cancelled: ${s.name}"
                                    }
                                }
                            }
                        }
                }
            }
            // Recording Favourites (a Jellyfin plugin's: not on NextPVR).
            if (!Jdvr.isNextPvr) Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Recording Favourites", color = Jc.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("JellyDVR Schedule's: by title, on any channel", color = Jc.Muted, fontSize = 13.sp)
                Spacer(8)
                val fav = favourites
                if (fav?.canManage == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Jc.Text, fontSize = 16.sp),
                            cursorBrush = SolidColor(Jc.Focus),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .border(BorderStroke(2.dp, Jc.Line), RoundedCornerShape(10.dp))
                                    .background(Jc.Panel2, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            decorationBox = { inner ->
                                if (newTitle.isEmpty()) Text("Title or keyword", color = Jc.Muted, fontSize = 16.sp)
                                inner()
                            },
                        )
                        Button(if (keyword) "Keyword" else "Exact title") { keyword = !keyword }
                        Button("+ Add", primary = true) {
                            val t = newTitle.trim()
                            if (t.isNotEmpty()) {
                                newTitle = ""
                                act { Jdvr.liveTv.addFavouriteByTitle(t, keyword)?.message }
                            }
                        }
                    }
                    Spacer(10)
                }
                when {
                    fav == null && !favouritesLoaded -> Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                    fav == null -> Text("Recording Favourites need the JellyDVR Schedule plugin on the server.", color = Jc.Muted, fontSize = 15.sp)
                    fav.favourites.isEmpty() -> Text("No recording favourites yet.", color = Jc.Muted, fontSize = 15.sp)
                    else ->
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(fav.favourites.sortedBy { it.title.lowercase() }, key = { it.id }) { f ->
                                FavouriteRow(
                                    f = f,
                                    canManage = fav.canManage,
                                    modifier = if (series.isNullOrEmpty() && f == fav.favourites.minByOrNull { it.title.lowercase() }) Modifier.focusRequester(entry) else Modifier,
                                    onToggle = { act { Jdvr.liveTv.setFavouriteEnabled(f.id, !f.enabled)?.message } },
                                    onRemove = { act { Jdvr.liveTv.removeFavourite(f.id)?.message } },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun SeriesRow(
    s: SeriesTimerInfoDto,
    modifier: Modifier,
    onEdit: () -> Unit,
    rank: Int? = null,
    onMove: ((Boolean) -> Unit)? = null,
    onCancel: () -> Unit,
) {
    var armed by remember(s.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    Column(
        Modifier.fillMaxWidth().background(Jc.Panel, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text((rank?.let { "$it · " } ?: "") + (s.name ?: ""), color = Jc.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val details =
            listOfNotNull(
                if (s.recordAnyChannel == true) "any channel" else s.channelName,
                if (s.recordAnyTime == true) "any time" else null,
                // NextPVR's timeslot rules: their slot in place of which episodes.
                s.overview ?: if (s.recordNewOnly == true) "new episodes only" else "all episodes",
                if (s.skipEpisodesInLibrary == true) "skips ones in the library" else null,
                padding(s.prePaddingSeconds, "early"),
                padding(s.postPaddingSeconds, "late"),
                s.keepUpTo?.takeIf { it > 0 }?.let { "keeps the latest $it" },
            ).joinToString(" · ")
        Text(details, color = Jc.Muted, fontSize = 14.sp)
        Row(Modifier.padding(top = 4.dp)) {
            Button("Edit", modifier = modifier.padding(end = 8.dp), onClick = onEdit)
            // Both on every row (at the top or bottom they do nothing), so a move never takes away
            // the button focus is on.
            onMove?.let { move ->
                Button("▲ Higher", modifier = Modifier.padding(end = 8.dp)) { move(true) }
                Button("▼ Lower", modifier = Modifier.padding(end = 8.dp)) { move(false) }
            }
            Button(if (armed) "Press again: cancel the series" else "Cancel series recording", leading = { SeriesDots(height = 14.dp, crossed = true) }) {
                if (armed) onCancel() else armed = true
            }
        }
    }
}

@Composable
private fun FavouriteRow(
    f: LiveTvRepository.FavouriteEntry,
    canManage: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    var armed by remember(f.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    Column(
        Modifier.fillMaxWidth().background(Jc.Panel, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("★", color = if (f.enabled) Jc.Gold else Jc.Muted, fontSize = 18.sp)
            Text(f.title, color = if (f.enabled) Jc.Text else Jc.Muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (!f.enabled) Chip("off", Jc.Muted)
        }
        Text(
            listOfNotNull(
                if (f.match == "keyword") "keyword" else "exact title",
                if (f.channels.isBlank()) "any channel" else "channels ${f.channels}",
                if (f.newOnly) "new episodes only" else null,
            ).joinToString(" · "),
            color = Jc.Muted,
            fontSize = 14.sp,
        )
        val next =
            f.next.joinToString("  ·  ") {
                runCatching { LocalDateTime.ofInstant(Instant.parse(it.start), ZoneId.systemDefault()) }.getOrNull()?.let { t -> "${t.day()} ${t.time()} ${it.channel}" } ?: ""
            }
        Text(
            when {
                !f.enabled -> "Switched off: records nothing new."
                next.isEmpty() -> "Nothing scheduled: not in the guide right now."
                else -> "Next: $next" + if (f.upcoming > f.next.size) "  (+${f.upcoming - f.next.size} more)" else ""
            },
            color = Jc.Text,
            fontSize = 14.sp,
        )
        if (canManage) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(if (f.enabled) "Switch off" else "Switch on", modifier = modifier, onClick = onToggle)
                Button(if (armed) "Press again: remove" else "Remove") { if (armed) onRemove() else armed = true }
            }
        }
    }
}

private fun padding(
    seconds: Int?,
    word: String,
): String? = seconds?.takeIf { it > 0 }?.let { "${(it + 30) / 60} min $word" }

private val PrePad = listOf(0, 60, 120, 300, 600)
private val PostPad = listOf(0, 60, 120, 300, 600, 900, 1800, 3600)
private val KeepCounts = listOf(0, 1, 3, 5, 10)

private fun <T> nextOf(
    list: List<T>,
    current: T,
): T = list[(list.indexOf(current) + 1).mod(list.size)]

/**
 * A series recording's options, as in the web client's series recording editor: new episodes or
 * all, this channel or any, this time or any, skip ones already in a library, start early / end
 * late, how many to keep. OK on a row steps through its choices; Save sends them.
 */
@Composable
private fun SeriesEditor(
    timer: SeriesTimerInfoDto,
    onSave: (SeriesTimerInfoDto) -> Unit,
    onCancel: () -> Unit,
) {
    var t by remember { mutableStateOf(timer) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val start = t.startDate?.let { java.time.LocalDateTime.ofInstant(it.atZone(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneId.systemDefault()).time() }
    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t.name ?: "Series recording", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Series recording options · OK changes a row · Back cancels", color = Jc.Muted, fontSize = 14.sp)
        Spacer(4)
        EditRow("Episodes", "Which airings to record.", if (t.recordNewOnly == true) "New episodes only" else "All episodes", Modifier.focusRequester(first)) {
            t = t.copy(recordNewOnly = t.recordNewOnly != true)
        }
        EditRow("Channel", "Only the channel it was set up on, or wherever it airs.", if (t.recordAnyChannel == true) "Any channel" else "Only ${t.channelName ?: "this channel"}") {
            t = t.copy(recordAnyChannel = t.recordAnyChannel != true)
        }
        EditRow("Time", "Only around the time it was set up for, or any airing.", if (t.recordAnyTime == true) "Any time" else "Only around ${start ?: "this time"}") {
            t = t.copy(recordAnyTime = t.recordAnyTime != true)
        }
        EditRow("Skip episodes already in a library", "Don't record an episode you already have.", if (t.skipEpisodesInLibrary == true) "Skip them" else "Record them anyway") {
            t = t.copy(skipEpisodesInLibrary = t.skipEpisodesInLibrary != true)
        }
        EditRow("Start early", "Begin recording before the guide's start time.", padding(t.prePaddingSeconds, "early") ?: "On time") {
            t = t.copy(prePaddingSeconds = nextOf(PrePad, PrePad.minByOrNull { kotlin.math.abs(it - (t.prePaddingSeconds ?: 0)) }!!))
        }
        EditRow("End late", "Keep recording after the guide's end time (for overruns).", padding(t.postPaddingSeconds, "late") ?: "On time") {
            t = t.copy(postPaddingSeconds = nextOf(PostPad, PostPad.minByOrNull { kotlin.math.abs(it - (t.postPaddingSeconds ?: 0)) }!!))
        }
        EditRow("Keep", "How many recordings of this series to keep.", (t.keepUpTo ?: 0).let { if (it <= 0) "All of them" else "The latest $it" }) {
            t = t.copy(keepUpTo = nextOf(KeepCounts, KeepCounts.minByOrNull { kotlin.math.abs(it - (t.keepUpTo ?: 0)) }!!))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Save", primary = true) { onSave(t) }
            Button("Cancel", onClick = onCancel)
        }
    }
}

@Composable
internal fun EditRow(
    label: String,
    help: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(0.8f),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, Jc.Focus))),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(help, color = Jc.Muted, fontSize = 13.sp)
            }
            Text(value, color = Jc.Focus, fontSize = 16.sp, modifier = Modifier.width(280.dp))
        }
    }
}
