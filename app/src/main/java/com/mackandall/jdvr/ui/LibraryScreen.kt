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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.mackandall.jdvr.data.letterOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val PAGE = 120

private fun clockOf(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

/** Where [LiveTvRepository.ItemDetails.location] says a file is in the live recordings folder. */
const val LIVE_FOLDER = "Live recordings folder"

/**
 * One of Jellyfin's libraries (Recorded Movies, Movies Archive, TV Archive …; each has its own menu
 * entry): its movies and shows as posters (A–Z or newest first), a show's seasons and episodes, a movie's details with
 * Resume / Play from start / Mark watched, and the recording actions (archive copies: the
 * commercials and audio tools only, nothing deletes them).
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
    // What was opened (and where in the list), to put focus back on it after Back.
    var lastItem by remember { mutableStateOf<UUID?>(null) }
    var lastIndex by remember { mutableIntStateOf(0) }
    // Hold OK on a poster: its quick actions; a change there (watched, deleted) reloads the grid.
    var quick by remember { mutableStateOf<LiveTvRepository.LibItem?>(null) }
    var changed by remember { mutableIntStateOf(0) }

    val m = movie
    val s = show
    when {
        m != null -> {
            BackHandler { movie = null }
            DetailsPage(m.id, refresh, onPlay, actions = { det, say ->
                // Live-folder recordings can be managed; archive copies get the tools only.
                RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) { movie = null }
            })
        }
        s != null -> {
            BackHandler { show = null }
            ShowPage(s, refresh, onPlay)
        }
        else ->
            LibraryGrid(
                library,
                entry,
                lastItem,
                lastIndex,
                refresh + changed,
                onHold = { it, i ->
                    lastItem = it.id
                    lastIndex = i
                    holdOk(menu = { quick = it }, details = { if (it.type == "Series") show = it else movie = it })
                },
            ) { it, i ->
                lastItem = it.id
                lastIndex = i
                if (it.type == "Series") show = it else movie = it
            }
    }
    quick?.let { q ->
        QuickActions(
            itemId = q.id,
            onPlay = { fromStart -> onPlay(PlayRecording(q.id, startMs = if (fromStart) 0L else null)) },
            onDetails = { movie = q },
            onChanged = { changed++ },
            onDismiss = { quick = null },
            onOpenShow = if (q.type == "Series") { { show = q } } else null,
            extra = { det, say, done ->
                if (q.type != "Series") {
                    RecordingActions(det.item.id, say, tools = false, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location, onDeleted = done)
                } else {
                    ShowActions(q.id, say, onDeleted = done)
                }
            },
        )
    }
}

@Composable
private fun LibraryGrid(
    library: LiveTvRepository.Library,
    entry: FocusRequester,
    restore: UUID?,
    restoreIndex: Int,
    refresh: Int,
    onHold: (LiveTvRepository.LibItem, Int) -> Unit,
    onOpen: (LiveTvRepository.LibItem, Int) -> Unit,
) {
    // Remembered per library, so it opens the way it was left.
    val newest = library.id.toString() in Jdvr.prefs.newestLibraries
    var items by remember(newest) { mutableStateOf<List<LiveTvRepository.LibItem>>(emptyList()) }
    var total by remember(newest) { mutableIntStateOf(-1) }
    // Bumped on every reload, so a page still arriving for the old list is dropped.
    var generation by remember { mutableIntStateOf(0) }
    val loadLock = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val grid = rememberLazyGridState()

    // A–Z only: every item's letter (same order as the grid), where each letter starts, and a
    // focus handle per letter for Left out of the grid's first column.
    var letters by remember(newest) { mutableStateOf<List<String>>(emptyList()) }
    val firstOf = remember(letters) { buildMap { letters.forEachIndexed { i, l -> putIfAbsent(l, i) } } }
    val letterReqs = remember { LETTERS.associateWith { FocusRequester() } }
    // The posters on screen, by index, to focus one after a jump.
    val itemReqs = remember { mutableMapOf<Int, FocusRequester>() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val currentLetter = letters.getOrNull(focusedIndex) ?: items.getOrNull(focusedIndex)?.let { letterOf(it.name) }

    // Loads pages until [through] is in the list (or the library ends).
    suspend fun fetch(through: Int) =
        loadLock.withLock {
            val gen = generation
            while (items.size <= through && (total < 0 || items.size < total)) {
                val (page, t) = runCatching { Jdvr.liveTv.libraryItems(library.id, newest, items.size, PAGE) }.getOrNull() ?: break
                if (gen != generation) break
                items = items + page
                total = t
                if (page.isEmpty()) break
            }
        }

    fun loadMore() {
        if (!loadLock.isLocked) scope.launch { fetch(items.size) }
    }
    val movesFinished by MoveWatcher.finished.collectAsState()
    LaunchedEffect(newest, refresh, movesFinished) {
        generation++
        items = emptyList()
        total = -1
        // Back from an item far down the list: load up to it and scroll there, so its poster can take focus.
        fetch(restoreIndex + 21)
        if (restore != null && restoreIndex > 0) runCatching { grid.scrollToItem(restoreIndex) }
        letters = if (newest) emptyList() else runCatching { Jdvr.liveTv.libraryLetters(library.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(items.isNotEmpty()) { if (items.isNotEmpty() && restore == null) runCatching { first.requestFocus() } }

    var jumpJob by remember { mutableStateOf<Job?>(null) }
    // Scrolls the grid to the letter's first item; with [focus], puts the cursor on it.
    fun jump(
        letter: String,
        focus: Boolean,
    ) {
        val idx = firstOf[letter] ?: return
        jumpJob?.cancel()
        jumpJob =
            scope.launch {
                fetch(idx + 35)
                runCatching { grid.scrollToItem(idx) }
                if (focus) {
                    repeat(20) {
                        delay(40)
                        val r = itemReqs[idx] ?: return@repeat
                        if (runCatching { r.requestFocus() }.isSuccess) return@launch
                    }
                }
            }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(library.name, color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (total >= 0) Text("$total", color = Jc.Muted, fontSize = 15.sp)
            Box(Modifier.weight(1f))
            Button(if (newest) "Recently added" else "A–Z") { Jdvr.prefs.changeLibraryNewest(library.id.toString(), !newest) }
        }
        Spacer(12)
        when {
            total < 0 -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            items.isEmpty() -> Text("Nothing in this library.", color = Jc.Muted, fontSize = 16.sp)
            else ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val strip = !newest && letters.isNotEmpty()
                    if (strip) {
                        LetterStrip(
                            present = firstOf.keys,
                            current = currentLetter,
                            reqs = letterReqs,
                            onPreview = { if (it != currentLetter) jump(it, focus = false) },
                            onEnter = { l ->
                                // Back to the poster it came from when the letter hasn't changed.
                                val back = itemReqs[focusedIndex].takeIf { l == currentLetter }
                                if (back == null || runCatching { back.requestFocus() }.isFailure) jump(l, focus = true)
                            },
                        )
                    }
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Fixed(7),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                            if (i >= items.size - 14) LaunchedEffect(items.size) { loadMore() }
                            val back = remember { FocusRequester() }
                            DisposableEffect(i, back) {
                                itemReqs[i] = back
                                onDispose { if (itemReqs[i] === back) itemReqs.remove(i) }
                            }
                            LaunchedEffect(Unit) { if (it.id == restore) runCatching { back.requestFocus() } }
                            val letter = letters.getOrNull(i) ?: letterOf(it.name)
                            Poster(
                                it,
                                (if (i == 0) Modifier.focusRequester(first).focusRequester(entry) else Modifier)
                                    .focusRequester(back)
                                    .onFocusChanged { f -> if (f.isFocused) focusedIndex = i }
                                    // First column: Left goes to this item's letter on the strip.
                                    .then(if (strip && i % 7 == 0) Modifier.focusProperties { left = letterReqs[letter] ?: FocusRequester.Default } else Modifier),
                                onHold = if (holdEnabled) { { onHold(it, i) } } else null,
                            ) { onOpen(it, i) }
                        }
                    }
                }
        }
    }
}

private val LETTERS = listOf("#") + ('A'..'Z').map { it.toString() }

/**
 * The A–Z strip beside the grid. Resting on a letter scrolls the grid there; OK or Right goes into
 * the grid at that letter's first item. Letters with nothing under them are dimmed and skipped.
 */
@Composable
private fun LetterStrip(
    present: Set<String>,
    current: String?,
    reqs: Map<String, FocusRequester>,
    onPreview: (String) -> Unit,
    onEnter: (String) -> Unit,
) {
    var focused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focused) {
        val l = focused ?: return@LaunchedEffect
        delay(250)
        onPreview(l)
    }
    Column(
        Modifier.width(30.dp).fillMaxHeight().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LETTERS.forEach { l ->
            val has = l in present
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (has) {
                    Surface(
                        onClick = { onEnter(l) },
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(1f)
                                .focusRequester(reqs.getValue(l))
                                .onFocusChanged { if (it.isFocused) focused = l else if (focused == l) focused = null }
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                        onEnter(l)
                                        true
                                    } else {
                                        false
                                    }
                                },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors =
                            ClickableSurfaceDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedContainerColor = Jc.FocusFill,
                                contentColor = if (l == current) Jc.Focus else Jc.Text,
                                focusedContentColor = Jc.Text,
                            ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.5f),
                    ) {
                        Text(l, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    Text(l, color = Jc.Line, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun Poster(
    item: LiveTvRepository.LibItem,
    modifier: Modifier,
    onHold: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onHold,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).then(onHold?.let { Modifier.onMenuKey(it) } ?: Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.liveTv.imageUrl(item.id, 360), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                movingFor(item.id.toString())?.let { MovingOverlay(it) } ?: cuttingFor(item.id.toString())?.let { CuttingOverlay(it) }
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
internal fun ShowPage(
    show: LiveTvRepository.LibItem,
    refresh: Int,
    onPlay: (PlayRecording) -> Unit,
) {
    var episodes by remember { mutableStateOf<List<EpisodeEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var episodeInfo by remember { mutableStateOf<UUID?>(null) }
    var quick by remember { mutableStateOf<EpisodeEntry?>(null) }
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
        DetailsPage(id, refresh, onPlay, actions = { det, say ->
            RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) {
                episodeInfo = null
                reload++
            }
        })
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
        onShowChanged = { reload++ },
        onToggleWatched = { e ->
            scope.launch {
                runCatching { Jdvr.liveTv.setPlayed(e.id, !e.played) }
                reload++
            }
        },
        onHold = { quick = it },
    )
    quick?.let { q ->
        QuickActions(
            itemId = q.id,
            onPlay = { fromStart -> onPlay(PlayRecording(q.id, startMs = if (fromStart) 0L else null)) },
            onDetails = { episodeInfo = q.id },
            onChanged = { reload++ },
            onDismiss = { quick = null },
            extra = { det, say, done -> RecordingActions(det.item.id, say, tools = false, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location, onDeleted = done) },
        )
    }
}
