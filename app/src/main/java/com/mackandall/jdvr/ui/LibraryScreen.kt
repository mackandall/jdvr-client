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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.launch
import java.util.UUID

private const val PAGE = 120

private fun clockOf(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/**
 * One of Jellyfin's libraries (Recorded Movies, Movies Archive, TV Archive …; each has its own menu
 * entry): its movies and shows as posters (A–Z or newest first), a show's seasons and episodes, a movie's details with
 * Resume / Play from start / Mark watched. Read and play only: nothing here changes the files.
 * [refresh] changes when a player closes, so watched marks and resume points update.
 */
@Composable
fun LibraryScreen(
    library: LiveTvRepository.Library,
    entry: FocusRequester,
    refresh: Int,
    onPlay: (PlayRecording) -> Unit,
) {
    var show by remember { mutableStateOf<LiveTvRepository.LibItem?>(null) }
    var movie by remember { mutableStateOf<LiveTvRepository.LibItem?>(null) }
    // What was opened, to put focus back on it after Back.
    var lastItem by remember { mutableStateOf<UUID?>(null) }

    val m = movie
    val s = show
    when {
        m != null -> {
            BackHandler { movie = null }
            DetailsPage(m.id, refresh, onPlay)
        }
        s != null -> {
            BackHandler { show = null }
            ShowPage(s, refresh, onPlay)
        }
        else ->
            LibraryGrid(library, entry, lastItem, refresh) {
                lastItem = it.id
                if (it.type == "Series") show = it else movie = it
            }
    }
}

@Composable
private fun LibraryGrid(
    library: LiveTvRepository.Library,
    entry: FocusRequester,
    restore: UUID?,
    refresh: Int,
    onOpen: (LiveTvRepository.LibItem) -> Unit,
) {
    var newest by remember { mutableStateOf(false) }
    var items by remember(newest) { mutableStateOf<List<LiveTvRepository.LibItem>>(emptyList()) }
    var total by remember(newest) { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        scope.launch {
            runCatching { Jdvr.liveTv.libraryItems(library.id, newest, items.size, PAGE) }.getOrNull()?.let { (page, t) ->
                items = items + page
                total = t
            }
            loading = false
        }
    }
    LaunchedEffect(newest, refresh) {
        items = emptyList()
        total = -1
        loading = false
        loadMore()
    }
    LaunchedEffect(items.isNotEmpty()) { if (items.isNotEmpty() && restore == null) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(library.name, color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (total >= 0) Text("$total", color = Jc.Muted, fontSize = 15.sp)
            Box(Modifier.weight(1f))
            Button(if (newest) "Recently added" else "A–Z") { newest = !newest }
        }
        Spacer(12)
        when {
            total < 0 -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            items.isEmpty() -> Text("Nothing in this library.", color = Jc.Muted, fontSize = 16.sp)
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                        if (i >= items.size - 14) LaunchedEffect(items.size) { loadMore() }
                        val back = remember { FocusRequester() }
                        LaunchedEffect(Unit) { if (it.id == restore) runCatching { back.requestFocus() } }
                        Poster(it, (if (i == 0) Modifier.focusRequester(first).focusRequester(entry) else Modifier).focusRequester(back)) { onOpen(it) }
                    }
                }
        }
    }
}

@Composable
private fun Poster(
    item: LiveTvRepository.LibItem,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.liveTv.imageUrl(item.id, 360), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.BottomStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        item.played -> Tag("✓", Jc.Green)
                        (item.unplayed ?: 0) > 0 -> Tag("${item.unplayed}", Jc.FocusFill)
                    }
                }
                if (item.resumeMs > 0 && !item.played && item.runtimeMin != null) {
                    val f = (item.resumeMs / 60_000f / item.runtimeMin).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Jc.Line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Jc.Focus))
                    }
                }
            }
        }
        Text(item.name, color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let { Text("$it", color = Jc.Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun Tag(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) = Text(text, color = Jc.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = 0.92f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))

/** A show: all its episodes in the episode browser (list with details, or tiles by season). */
@Composable
private fun ShowPage(
    show: LiveTvRepository.LibItem,
    refresh: Int,
    onPlay: (PlayRecording) -> Unit,
) {
    var episodes by remember { mutableStateOf<List<EpisodeEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var episodeInfo by remember { mutableStateOf<UUID?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(show.id, refresh, reload) {
        episodes =
            runCatching { Jdvr.liveTv.episodes(show.id, null) }.getOrNull()?.map {
                EpisodeEntry(it.id, it.name, it.season, it.episode, it.played, it.resumeMs, it.runtimeMin, it.overview)
            }?.let { withCommercialBadges(it) } ?: emptyList()
    }

    // An episode's own details page.
    episodeInfo?.let { id ->
        BackHandler { episodeInfo = null }
        DetailsPage(id, refresh, onPlay)
        return
    }
    EpisodeBrowser(
        seriesId = show.id,
        title = show.name,
        subtitle = listOfNotNull(show.year?.toString(), show.officialRating, episodes?.let { "${it.size} episodes" }).joinToString(" · "),
        entries = episodes,
        entry = null,
        onPlay = { onPlay(PlayRecording(it.id)) },
        onPlayFromStart = { onPlay(PlayRecording(it.id, startMs = 0L)) },
        onInfo = { episodeInfo = it.id },
        onToggleWatched = { e ->
            scope.launch {
                runCatching { Jdvr.liveTv.setPlayed(e.id, !e.played) }
                reload++
            }
        },
    )
}
