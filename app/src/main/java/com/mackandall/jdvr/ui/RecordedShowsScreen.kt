package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private fun local(iso: String?): LocalDateTime? = iso?.let { runCatching { LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()) }.getOrNull() }

/**
 * Recorded Shows, as in the web client (Episode History's list): what the DVR recorded, newest first,
 * recordings folder only for now. A show opens its episodes; an episode or movie plays.
 */
@Composable
fun RecordedShowsScreen(
    entry: FocusRequester,
    onPlay: (UUID) -> Unit,
    openShowId: String? = null,
    onShowOpened: () -> Unit = {},
    onLeave: () -> Unit = {},
    refresh: Int = 0,
    onPlayItem: (com.mackandall.jdvr.PlayRecording) -> Unit = { onPlay(it.itemId) },
) {
    // A movie or one-off recording's details page (from the grid).
    var details by remember { mutableStateOf<UUID?>(null) }
    var badges by remember { mutableStateOf<Map<String, LiveTvRepository.CommercialBadge>>(emptyMap()) }
    var reloadCards by remember { mutableStateOf(0) }
    // Opened on a show from elsewhere (Home): Back from it goes back there, not to the grid.
    val cameForShow = remember { openShowId != null }
    var cards by remember { mutableStateOf<List<LiveTvRepository.RecordingCard>?>(null) }
    // Without Episode History: Jellyfin's own recordings, one card each (no live folder / archive).
    var plain by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var openShow by remember { mutableStateOf<LiveTvRepository.RecordingCard?>(null) }
    // Hold OK on a card: its quick actions.
    var quick by remember { mutableStateOf<LiveTvRepository.RecordingCard?>(null) }
    // Hold OK on a line of the list by date: its quick actions.
    var quickLine by remember { mutableStateOf<UUID?>(null) }
    // The list by date needs a newer Episode History; without it, the cards.
    var noDateList by remember { mutableStateOf(false) }
    val byDate = Jdvr.prefs.recordingsView == Prefs.BY_DATE && !noDateList

    val filter = Jdvr.prefs.recordingsFilter
    // A move to the archive finished: the recording now lives elsewhere.
    val movesFinished by MoveWatcher.finished.collectAsState()
    LaunchedEffect(filter, reloadCards, movesFinished) {
        loaded = false
        val shows = Jdvr.liveTv.recordedShows(filter)
        plain = shows == null
        cards = shows ?: Jdvr.liveTv.jellyfinRecordings()
        // Comskip's badges for the cards (a show's: how many of its recordings are scanned).
        badges = cards?.mapNotNull { it.id.toUuid() }?.let { ids -> runCatching { Jdvr.liveTv.commercialBadges(ids) }.getOrNull() }.orEmpty()
        loaded = true
        // Asked to open a show (from Home): straight to its episodes.
        openShowId?.let { id -> cards?.firstOrNull { it.id == id }?.let { openShow = it } }
        onShowOpened()
    }

    // Focus goes to the first card once the grid is there (it comes after the cards and their
    // badges load); only the first time, so switching the filter leaves focus on its button.
    var focusedFirst by remember { mutableStateOf(false) }
    // The card a show or details page was opened from: Back from there lands on it again (closing
    // that page removes the focused button, and Android otherwise leaves nothing focused).
    var returnTo by remember { mutableStateOf<String?>(null) }
    val back = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    details?.let { id ->
        // The list behind is loaded again: what was done on the page (a stop, a scan) shows.
        BackHandler { details = null; reloadCards++ }
        DetailsPage(id, refresh, onPlay = onPlayItem, actions = { det, say ->
            RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) {
                details = null
                reloadCards++
            }
        })
        return
    }
    val show = openShow
    if (show != null) {
        BackHandler { if (cameForShow) onLeave() else openShow = null }
        ShowEpisodes(show, entry, filter, refresh, onPlay, onPlayItem)
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Recorded Shows", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (plain) "Jellyfin's recordings · newest first" else when (filter) {
                        Prefs.ARCHIVED -> "Moved to the archive · newest first"
                        Prefs.ALL -> "Recordings folder and archive · newest first"
                        else -> "Recordings folder · newest first"
                    },
                    color = Jc.Muted,
                    fontSize = 15.sp,
                )
            }
            // Cards per show, or one line per recording by date with its channel.
            if (!plain) Button(if (byDate) "View: By date" else "View: By show") {
                Jdvr.prefs.changeRecordingsView(if (Jdvr.prefs.recordingsView == Prefs.BY_DATE) Prefs.SHOWS else Prefs.BY_DATE)
            }
            // Live folder → Archive → Both (Episode History's; not on a plain server).
            if (!plain) Button(
                when (filter) {
                    Prefs.ARCHIVED -> "Showing: Archive"
                    Prefs.ALL -> "Showing: Both"
                    else -> "Showing: Live folder"
                },
            ) {
                Jdvr.prefs.changeRecordingsFilter(
                    when (filter) {
                        Prefs.CURRENT -> Prefs.ARCHIVED
                        Prefs.ARCHIVED -> Prefs.ALL
                        else -> Prefs.CURRENT
                    },
                )
            }
        }
        Spacer(14)
        val list = cards
        when {
            byDate && !plain && loaded -> RecordingsByDate(
                filter = filter,
                reload = reloadCards,
                entry = entry,
                onPlay = onPlay,
                onHold = { quickLine = it },
                onDetails = { details = it },
                onUnavailable = { noDateList = true },
            )
            !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            list == null -> Text("Couldn't load the recordings from the server.", color = Jc.Amber, fontSize = 16.sp)
            list.isEmpty() -> Text(if (filter == Prefs.ARCHIVED) "Nothing has been moved to the archive." else "Nothing recorded yet.", color = Jc.Muted, fontSize = 16.sp)
            else -> {
                LaunchedEffect(Unit) {
                    returnTo?.let { id ->
                        val index = list.indexOfFirst { it.id == id }
                        if (index >= 0) {
                            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
                            repeat(40) {
                                if (runCatching { back.requestFocus() }.isSuccess) {
                                    returnTo = null
                                    return@LaunchedEffect
                                }
                                delay(50)
                            }
                        }
                        // The card is gone (deleted or moved): the first card instead.
                        returnTo = null
                        focusedFirst = false
                    }
                    if (focusedFirst) return@LaunchedEffect
                    focusedFirst = true
                    repeat(40) {
                        if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
                        delay(50)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        var m: Modifier = Modifier
                        if (i == 0) m = m.focusRequester(entry)
                        if (c.id == returnTo) m = m.focusRequester(back)
                        RecordingCardView(
                            c,
                            badges[c.id.plainId()],
                            m,
                            onHold = if (!holdEnabled) null else { {
                                returnTo = c.id
                                holdOk(menu = { quick = c }, details = { if (c.type == "Series") openShow = c else details = c.id.toUuid() })
                            } },
                        ) {
                            returnTo = c.id
                            if (c.type == "Series") openShow = c else details = c.id.toUuid()
                        }
                    }
                }
            }
        }
    }
    quickLine?.let { id ->
        QuickActions(
            itemId = id,
            onPlay = { fromStart -> onPlayItem(com.mackandall.jdvr.PlayRecording(id, startMs = if (fromStart) 0L else null)) },
            onDetails = { details = id },
            onChanged = { reloadCards++ },
            onDismiss = { quickLine = null; reloadCards++ },
            extra = { det, say, done ->
                RecordingActions(det.item.id, say, tools = false, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location, onDeleted = done)
            },
        )
    }
    val q = quick
    val qid = q?.id?.toUuid()
    if (q != null && qid != null) {
        QuickActions(
            itemId = qid,
            onPlay = { fromStart -> onPlayItem(com.mackandall.jdvr.PlayRecording(qid, startMs = if (fromStart) 0L else null)) },
            onDetails = { details = qid },
            onChanged = { reloadCards++ },
            onDismiss = { quick = null; reloadCards++ },
            onOpenShow = if (q.type == "Series") { { openShow = q } } else null,
            extra = { det, say, done ->
                if (q.type != "Series") {
                    RecordingActions(det.item.id, say, tools = false, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location, onDeleted = done)
                } else {
                    ShowActions(qid, say, onDeleted = done)
                }
            },
        )
    }
}

@Composable
private fun RecordingCardView(
    c: LiveTvRepository.RecordingCard,
    commercials: LiveTvRepository.CommercialBadge?,
    modifier: Modifier,
    onHold: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onHold,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).then(onHold?.let { Modifier.onMenuKey(it) } ?: Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val image = (c.imageId ?: c.id).let { Jdvr.liveTv.imageUrl(it, 400) }
                if (image != null) {
                    AsyncImage(model = image, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                movingFor(c.id)?.let { MovingOverlay(it) } ?: cuttingFor(c.id)?.let { CuttingOverlay(it) }
                // A badge that doesn't fit goes to its own line; its text never wraps inside the pill.
                FlowRow(
                    Modifier.align(Alignment.BottomStart).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (c.recordingCount > 0) Badge("● Recording", Jc.Recording)
                    // The plain count only when it says something "N new" doesn't: some are watched.
                    if (c.count > 1 && c.unplayedCount != c.count) Badge("${c.count}", Jc.Panel)
                    if (c.unplayedCount > 0 && c.count > 1) Badge("${c.unplayedCount} new", Jc.FocusFill)
                    commercials?.let { b -> commercialColor(b.state, b.failed == true).let { (color, dark) -> Badge("✂ ${b.label}", color, dark) } }
                    if (c.partialCount > 0) Badge(if (c.count == 1) "Partial${c.partialMinutes?.let { " · $it min" } ?: ""}" else "${c.partialCount} partial", Jc.Amber, dark = true)
                }
            }
        }
        Text(c.name, color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.subtitle?.let { Text(it, color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * A commercial badge's colour, as on the web client and Wholphin: green when ads were found, amber
 * while scanning, grey when there were none, red when the scan failed; a show's summary is green
 * (amber with one failed). The Boolean: dark text on it.
 */
@Composable
fun commercialColor(
    state: String?,
    anyFailed: Boolean = false,
): Pair<androidx.compose.ui.graphics.Color, Boolean> =
    when (state) {
        "found" -> Jc.Green to false
        "scanning" -> Jc.Amber to true
        "failed" -> Jc.Recording to false
        "summary" -> (if (anyFailed) Jc.Amber to true else Jc.Green to false)
        else -> Jc.Panel2 to false
    }

@Composable
private fun Badge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    dark: Boolean = false,
) {
    Text(
        text,
        color = badgeText(color, dark),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.background(color.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** A show's recordings, in the episode browser (list with details, or tiles); OK plays one. */
@Composable
private fun ShowEpisodes(
    show: LiveTvRepository.RecordingCard,
    entry: FocusRequester,
    filter: String,
    refresh: Int,
    onPlay: (UUID) -> Unit,
    onPlayItem: (com.mackandall.jdvr.PlayRecording) -> Unit,
) {
    var info by remember { mutableStateOf<UUID?>(null) }
    var quick by remember { mutableStateOf<EpisodeEntry?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeEntry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(show.id, refresh, reload) {
        episodes =
            Jdvr.liveTv.showRecordings(show.id, filter)?.mapNotNull { e ->
                e.id.toUuid()?.let { id ->
                    EpisodeEntry(
                        id = id,
                        name = e.name,
                        season = e.season,
                        episode = e.episode,
                        played = e.played,
                        recordedAt = local(e.recordedUtc),
                        archived = e.archived && filter == Prefs.ALL,
                        inArchive = e.archived,
                        partial = e.partial,
                        partialMinutes = e.minutes,
                        recording = e.recording,
                    )
                }
            }?.sortedWith(compareBy<EpisodeEntry>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.recordedAt }))?.let { withCommercialBadges(it) } ?: emptyList()
    }
    info?.let { id ->
        BackHandler { info = null; reload++ }
        DetailsPage(id, refresh, onPlay = onPlayItem, actions = { det, say ->
            RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) {
                info = null
                reload++
            }
        })
        return
    }
    EpisodeBrowser(
        seriesId = show.id.toUuid(),
        title = show.name,
        subtitle = episodes?.let { "${it.size} recording" + (if (it.size == 1) "" else "s") },
        entries = episodes,
        entry = entry,
        onPlay = { onPlay(it.id) },
        onPlayFromStart = { onPlayItem(com.mackandall.jdvr.PlayRecording(it.id, startMs = 0L)) },
        onInfo = { info = it.id },
        onShowChanged = { reload++ },
        onToggleWatched = { e ->
            scope.launch {
                runCatching { Jdvr.liveTv.setPlayed(e.id, !e.played) }
                reload++
            }
        },
        actions = { e, say -> RecordingActions(e.id, say, onDeleted = { reload++ }, tools = false, manage = !e.inArchive) },
        emptyText = "No recordings of this show here.",
        onHold = { quick = it },
    )
    quick?.let { q ->
        QuickActions(
            itemId = q.id,
            onPlay = { fromStart -> if (fromStart) onPlayItem(com.mackandall.jdvr.PlayRecording(q.id, startMs = 0L)) else onPlay(q.id) },
            onDetails = { info = q.id },
            onChanged = { reload++ },
            onDismiss = { quick = null; reload++ },
            // Only a recording in the live folder can be deleted or moved from here (never an archive copy).
            extra = { det, say, done -> RecordingActions(det.item.id, say, tools = false, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location, onDeleted = done) },
        )
    }
}
