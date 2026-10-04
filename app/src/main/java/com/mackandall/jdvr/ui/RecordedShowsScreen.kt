package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
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
) {
    // Opened on a show from elsewhere (Home): Back from it goes back there, not to the grid.
    val cameForShow = remember { openShowId != null }
    var cards by remember { mutableStateOf<List<LiveTvRepository.RecordingCard>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var openShow by remember { mutableStateOf<LiveTvRepository.RecordingCard?>(null) }

    val filter = Jdvr.prefs.recordingsFilter
    LaunchedEffect(filter) {
        loaded = false
        cards = Jdvr.liveTv.recordedShows(filter)
        loaded = true
        // Asked to open a show (from Home): straight to its episodes.
        openShowId?.let { id -> cards?.firstOrNull { it.id == id }?.let { openShow = it } }
        onShowOpened()
    }

    val show = openShow
    if (show != null) {
        BackHandler { if (cameForShow) onLeave() else openShow = null }
        ShowEpisodes(show, entry, filter, onPlay)
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
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        RecordingCardView(c, if (i == 0) Modifier.focusRequester(entry) else Modifier) {
                            if (c.type == "Series") openShow = c else c.id.toUuid()?.let(onPlay)
                        }
                    }
                }
        }
    }
}

@Composable
private fun RecordingCardView(
    c: LiveTvRepository.RecordingCard,
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
                Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (c.count > 1) Badge("${c.count}", Jc.Panel)
                    if (c.unplayedCount > 0 && c.count > 1) Badge("${c.unplayedCount} new", Jc.FocusFill)
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
        modifier = Modifier.background(color.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** A show's recordings, newest first; OK plays one. */
@Composable
private fun ShowEpisodes(
    show: LiveTvRepository.RecordingCard,
    entry: FocusRequester,
    filter: String,
    onPlay: (UUID) -> Unit,
) {
    var episodes by remember { mutableStateOf<List<LiveTvRepository.EpisodeRecording>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(show.id) {
        episodes = Jdvr.liveTv.showRecordings(show.id, filter)
        loaded = true
    }
    LaunchedEffect(episodes) { if (!episodes.isNullOrEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Text(show.name, color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Back for all Recorded Shows", color = Jc.Muted, fontSize = 14.sp)
        Spacer(14)
        val list = episodes
        when {
            !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            list.isNullOrEmpty() -> Text("No recordings of this show here.", color = Jc.Muted, fontSize = 16.sp)
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                        Surface(
                            onClick = { e.id.toUuid()?.let(onPlay) },
                            modifier = (if (i == 0) Modifier.focusRequester(first).focusRequester(entry) else Modifier).fillMaxWidth().height(60.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(local(e.recordedUtc)?.let { "${it.day()} ${it.time()}" } ?: "", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.width(150.dp))
                                Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (e.archived && filter == Prefs.ALL) Badge("Archive", Jc.Panel2)
                                if (e.partial) Badge("Partial${e.minutes?.let { " · $it min" } ?: ""}", Jc.Amber, dark = true)
                                if (e.played) Chip("watched", Jc.Green)
                            }
                        }
                    }
                }
        }
    }
}
