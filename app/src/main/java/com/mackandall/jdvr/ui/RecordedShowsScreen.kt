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
    var loaded by remember { mutableStateOf(false) }
    var openShow by remember { mutableStateOf<LiveTvRepository.RecordingCard?>(null) }

    val filter = Jdvr.prefs.recordingsFilter
    // A move to the archive finished: the recording now lives elsewhere.
    val movesFinished by MoveWatcher.finished.collectAsState()
    LaunchedEffect(filter, reloadCards, movesFinished) {
        loaded = false
        cards = Jdvr.liveTv.recordedShows(filter)
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

    details?.let { id ->
        BackHandler { details = null }
        DetailsPage(id, refresh, onPlay = onPlayItem, actions = { det, say ->
            RecordingActions(det.item.id, say) {
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
                    when (filter) {
                        Prefs.ARCHIVED -> "Moved to the archive · newest first"
                        Prefs.ALL -> "Recordings folder and archive · newest first"
                        else -> "Recordings folder · newest first"
                    },
                    color = Jc.Muted,
                    fontSize = 15.sp,
                )
            }
            // Live folder → Archive → Both.
            Button(
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
            !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            list == null -> Text("Couldn't load Recorded Shows (the Episode History plugin answers this).", color = Jc.Amber, fontSize = 16.sp)
            list.isEmpty() -> Text(if (filter == Prefs.ARCHIVED) "Nothing has been moved to the archive." else "Nothing recorded yet.", color = Jc.Muted, fontSize = 16.sp)
            else -> {
                LaunchedEffect(Unit) {
                    if (focusedFirst) return@LaunchedEffect
                    focusedFirst = true
                    repeat(40) {
                        if (runCatching { entry.requestFocus() }.isSuccess) return@LaunchedEffect
                        delay(50)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        RecordingCardView(c, badges[c.id.plainId()], if (i == 0) Modifier.focusRequester(entry) else Modifier) {
                            if (c.type == "Series") openShow = c else details = c.id.toUuid()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingCardView(
    c: LiveTvRepository.RecordingCard,
    commercials: LiveTvRepository.CommercialBadge?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
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
                    // The plain count only when it says something "N new" doesn't: some are watched.
                    if (c.count > 1 && c.unplayedCount != c.count) Badge("${c.count}", Jc.Panel)
                    if (c.unplayedCount > 0 && c.count > 1) Badge("${c.unplayedCount} new", Jc.FocusFill)
                    commercials?.let { Badge("✂ ${it.label}", if (it.state == "failed") Jc.Recording else Jc.Panel) }
                    if (c.partialCount > 0) Badge(if (c.count == 1) "Partial${c.partialMinutes?.let { " · $it min" } ?: ""}" else "${c.partialCount} partial", Jc.Amber, dark = true)
                }
            }
        }
        Text(c.name, color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        c.subtitle?.let { Text(it, color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Badge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    dark: Boolean = false,
) {
    Text(
        text,
        color = if (dark) androidx.compose.ui.graphics.Color(0xFF1A1206) else Jc.Text,
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
                        partial = e.partial,
                        partialMinutes = e.minutes,
                    )
                }
            }?.sortedWith(compareBy<EpisodeEntry>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.recordedAt }))?.let { withCommercialBadges(it) } ?: emptyList()
    }
    info?.let { id ->
        BackHandler { info = null }
        DetailsPage(id, refresh, onPlay = onPlayItem, actions = { det, say ->
            RecordingActions(det.item.id, say) {
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
        onToggleWatched = { e ->
            scope.launch {
                runCatching { Jdvr.liveTv.setPlayed(e.id, !e.played) }
                reload++
            }
        },
        actions = { e, say -> RecordingActions(e.id, say, onDeleted = { reload++ }, tools = false) },
        emptyText = "No recordings of this show here.",
    )
}
