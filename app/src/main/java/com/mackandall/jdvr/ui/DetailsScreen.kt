package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.CommercialBadge
import com.mackandall.jdvr.data.ItemDetails
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Person
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.attemptResult
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

private val DateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun length(min: Long) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

/**
 * A movie, episode or recording, over its backdrop: title, ratings, genres, description, Play /
 * Resume / Mark watched, what's in the file (video, audio, subtitles, size, where it's stored) and
 * the credits with photos. [actions] adds buttons after the standard ones (Recorded Shows' own).
 */
@Composable
fun DetailsPage(
    itemId: UUID,
    refresh: Int,
    onPlay: (PlayRecording) -> Unit,
    actions: @Composable RowScope.(ItemDetails, (String) -> Unit) -> Unit = { _, _ -> },
) {
    var d by remember { mutableStateOf<ItemDetails?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var commercials by remember { mutableStateOf<CommercialBadge?>(null) }
    LaunchedEffect(itemId, refresh, CommercialMarks.changed) {
        commercials =
            attempt {
                Jdvr.backend
                    .commercialBadges(listOf(itemId))
                    .values
                    .firstOrNull()
            }
    }
    // Where this recording's show is stored, for "→ moves to …" under Stored.
    val rights = deviceRights()
    var storage by remember { mutableStateOf<com.mackandall.jdvr.data.ShowStorage?>(null) }
    LaunchedEffect(itemId, refresh, rights.archive) {
        storage = if (rights.archive) attempt { Jdvr.backend.showStorage(itemId) } else null
    }
    LaunchedEffect(note) {
        if (note != null) {
            kotlinx.coroutines.delay(4000)
            note = null
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val play = remember { FocusRequester() }
    LaunchedEffect(itemId, refresh) {
        attemptResult { Jdvr.backend.details(itemId) }.onSuccess { d = it }.onFailure { error = it.message }
    }
    LaunchedEffect(d != null) { if (d != null) play.tryFocus() }

    // A cast member's page, over this one; Back returns to their card.
    val people = rememberPersonReturn()
    people.opened?.let { p ->
        BackHandler { people.opened = null }
        PersonPage(p, onPlay)
        return
    }

    val det = d
    Box(Modifier.fillMaxSize().screenBackground()) {
        if (det == null) {
            Text(error?.let { "Couldn't load this: $it" } ?: "Loading…", color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.padding(36.dp))
            return@Box
        }
        val item = det.item
        // JDVR Embuary: its own details page (poster, title, Resume, chips, the season's episodes).
        if (Jc.Style == UiStyle.Embuary) {
            Backdrop(det.backdropOf?.let { Jdvr.backend.backdropUrl(it) }, Jdvr.backend.imageUrl(item.id, 720), strength = 0.92f)
            EmbuaryDetails(
                det = det,
                commercials = commercials,
                note = note,
                play = play,
                people = people,
                onPlay = onPlay,
                onToggleWatched = {
                    scope.launch {
                        attempt { Jdvr.backend.setPlayed(item.id, !item.played) }
                        attempt { d = Jdvr.backend.details(item.id) }
                    }
                },
                actions = { actions(det) { note = it } },
            )
            return@Box
        }
        // The backdrop at the top right, behind the text's right side; none: the poster, blurred.
        Backdrop(det.backdropOf?.let { Jdvr.backend.backdropUrl(it) }, Jdvr.backend.imageUrl(item.id, 720), strength = 0.85f)

        // TV's default scrolling pulls the focused button to ~30% down the screen, which cut off the
        // top of the page. Here it scrolls only as far as needed (to reach the cast), and back to
        // the top whenever the title and buttons get focus.
        val scroll = rememberScrollState()
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // The title, buttons and file boxes always fit on one screen: when a recording's many
                // actions make them taller than the screen, they shrink just enough. Cast scrolls below.
                val fitHeight = maxHeight - 56.dp
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 36.dp, top = 28.dp, end = 36.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.fitTo(fitHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            AsyncImage(
                                model = Jdvr.backend.imageUrl(item.id, 480),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .width(150.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Jc.Panel2),
                            )
                            Column(Modifier.fillMaxWidth(0.72f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (item.seriesName != null && item.type == "Episode") {
                                    Text(listOfNotNull(item.seriesName, item.seasonEpisode).joinToString(" · "), color = Jc.Muted, fontSize = 16.sp)
                                }
                                Text(item.name, color = Jc.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    (det.premiere?.format(DateFormat) ?: item.year?.toString())?.let { Text(it, color = Jc.Text, fontSize = 15.sp) }
                                    item.runtimeMin?.let { Text(length(it), color = Jc.Text, fontSize = 15.sp) }
                                    item.officialRating?.let { Text(it, color = Jc.Text, fontSize = 13.sp, modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) }
                                    det.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = 15.sp) }
                                    det.criticRating?.let { Text("${it.toInt()}% critics", color = Jc.Text, fontSize = 15.sp) }
                                    if (item.played) Chip("watched", Jc.Green)
                                }
                                if (det.genres.isNotEmpty()) Text(det.genres.joinToString(" · "), color = Jc.Muted, fontSize = 14.sp)
                                det.tagline?.let { Text(it, color = Jc.Text, fontSize = 15.sp, fontStyle = FontStyle.Italic) }
                                item.overview?.let { Text(it, color = Jc.Text, fontSize = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                                // Wraps onto a second line when a recording's actions make it long.
                                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    val resume = item.resumeMs > 0 && !item.played
                                    Button(if (resume) "Resume from ${length(item.resumeMs / 60_000)}" else "Play", primary = true, modifier = Modifier.focusRequester(play)) { onPlay(PlayRecording(item.id)) }
                                    if (resume) Button("Play from start") { onPlay(PlayRecording(item.id, startMs = 0L)) }
                                    Button(if (item.played) "Mark unwatched" else "Mark watched") {
                                        scope.launch {
                                            attempt { Jdvr.backend.setPlayed(item.id, !item.played) }
                                            attempt { d = Jdvr.backend.details(item.id) }
                                        }
                                    }
                                    actions(det) { note = it }
                                }
                                note?.let { Text(it, color = Jc.Focus, fontSize = 14.sp) }
                            }
                        }

                        // What's in the file.
                        // Wraps onto a second line rather than squeezing the last box into a sliver.
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            det.video?.let { Info("Video", it) }
                            if (det.audio.isNotEmpty()) Info("Audio", det.audio.first() + if (det.audio.size > 1) "  +${det.audio.size - 1}" else "")
                            if (det.subtitles.isNotEmpty()) Info("Subtitles", det.subtitles.take(2).joinToString(", ") + if (det.subtitles.size > 2) "  +${det.subtitles.size - 2}" else "")
                            det.file?.let { Info("File", it) }
                            det.location?.let { Info("Stored", it, storage?.takeIf { s -> s.onRecordingDrive && s.sortMode == "active" }?.movesTo?.let { to -> "→ moves to $to" }) }
                            commercials?.let { Info("Commercials", it.label) }
                        }
                    }

                    val credits =
                        listOfNotNull(
                            det.directors.takeIf { it.isNotEmpty() }?.let { "Directed by " + it.joinToString(", ") { p -> p.name } },
                            det.writers.takeIf { it.isNotEmpty() }?.let { "Written by " + it.take(3).joinToString(", ") { p -> p.name } },
                            det.studios.takeIf { it.isNotEmpty() }?.let { it.take(2).joinToString(", ") },
                        )
                    if (credits.isNotEmpty()) Text(credits.joinToString("  ·  "), color = Jc.Muted, fontSize = 14.sp)

                    if (det.cast.isNotEmpty()) {
                        Text("Cast", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(det.cast.take(30), key = { it.id.toString() + it.role }) { p -> CastCard(p, people.card(p.id)) { people.open(p) } }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shrinks the content (from its top left) when it is taller than [height], down to [floor] at most;
 * past that it keeps that size and the page scrolls. The space it takes shrinks with it.
 */
private fun Modifier.fitTo(
    height: Dp,
    floor: Float = 0.7f,
) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val limit = height.roundToPx()
    val s = if (p.height > limit && limit > 0) (limit.toFloat() / p.height).coerceAtLeast(floor) else 1f
    layout(p.width, (p.height * s).roundToInt()) {
        p.placeWithLayer(0, 0) {
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

@Composable
private fun Info(
    label: String,
    value: String,
    // A second line, e.g. where a recording moves to.
    sub: String? = null,
) {
    Column(Modifier.background(Jc.Panel.seeThrough(0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label.uppercase(), color = Jc.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Jc.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, color = Jc.Focus, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** One cast member: OK opens their page. */
@Composable
private fun CastCard(
    p: Person,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(96.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Ink.copy(alpha = 0f), focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Jc.Panel2), contentAlignment = Alignment.Center) {
                if (p.hasImage) {
                    AsyncImage(model = Jdvr.backend.personImageUrl(p.id), contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        p.name
                            .split(' ')
                            .mapNotNull { it.firstOrNull()?.toString() }
                            .take(2)
                            .joinToString(""),
                        color = Jc.Muted,
                        fontSize = 20.sp,
                    )
                }
            }
            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.role?.let { Text(it, color = Jc.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * JDVR Embuary's details page, after the Embuary skin: a thin top bar, the poster at the left,
 * title and episode, a meta line with the rating boxed, a green Resume, the genres in green, the
 * description, chips for what's in the file, then the season's episodes and the cast.
 */
@Composable
private fun EmbuaryDetails(
    det: ItemDetails,
    commercials: CommercialBadge?,
    note: String?,
    play: FocusRequester,
    people: PersonReturn,
    onPlay: (PlayRecording) -> Unit,
    onToggleWatched: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val item = det.item
    val accent = Jc.Green
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.time.LocalDateTime.now()
            kotlinx.coroutines.delay(5_000)
        }
    }
    // The rest of the season (or the show, without seasons), for the Episodes row.
    var episodes by remember(item.id) { mutableStateOf<List<com.mackandall.jdvr.data.LibItem>>(emptyList()) }
    LaunchedEffect(item.id, item.played, item.resumeMs) {
        val series = item.seriesId
        if (item.type == "Episode" && series != null) {
            episodes = attempt { Jdvr.backend.episodes(series, null) }.orEmpty().filter { item.season == null || it.season == item.season }
        }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        // The top bar.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("‹", color = Color.White, fontSize = 16.sp)
            Text(if (item.type == "Movie") "Movie details" else "Recording details", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Text(now.time(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 22.dp, end = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } }, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    AsyncImage(
                        model = Jdvr.backend.imageUrl(item.seriesId ?: item.id, 480),
                        contentDescription = item.seriesName ?: item.name,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(150.dp)
                                .aspectRatio(2f / 3f)
                                .background(Color(0xFF1C2A44)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.seriesName != null && item.type == "Episode") {
                                    Text(listOfNotNull(item.seriesName, item.seasonEpisode).joinToString(" · "), color = Color(0xFFCFD8E6), fontSize = 14.sp)
                                }
                            }
                            // Where Embuary shows the show's clear logo: its name in spaced capitals.
                            item.seriesName?.takeIf { item.type == "Episode" }?.let {
                                Text(
                                    it.uppercase(),
                                    color = Color(0xFFF0D7A8),
                                    fontSize = 20.sp,
                                    letterSpacing = 3.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier.width(220.dp).padding(start = 12.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            (det.premiere?.year ?: item.year)?.let { Text(it.toString(), color = Color.White, fontSize = 13.sp) }
                            item.runtimeMin?.let { Text(length(it), color = Color.White, fontSize = 13.sp) }
                            item.officialRating?.let { EmbuaryBox(it) }
                            if (det.subtitles.isNotEmpty()) EmbuaryBox("CC")
                            det.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = 13.sp) }
                            item.runtimeMin?.let { min ->
                                val left = (min * 60_000L - (if (item.played) 0L else item.resumeMs)).coerceAtLeast(0L)
                                Text("Ends at " + now.plusSeconds(left / 1000).time(), color = Color.White, fontSize = 13.sp)
                            }
                            if (item.played) Text("✓ watched", color = accent, fontSize = 13.sp)
                        }
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val resume = item.resumeMs > 0 && !item.played
                            Button(if (resume) "Resume from ${length(item.resumeMs / 60_000)}" else "Play", primary = true, compact = true, modifier = Modifier.focusRequester(play)) { onPlay(PlayRecording(item.id)) }
                            if (resume) Button("Play from start", compact = true) { onPlay(PlayRecording(item.id, startMs = 0L)) }
                            Button(if (item.played) "Mark unwatched" else "Mark watched", compact = true, onClick = onToggleWatched)
                            CompositionLocalProvider(LocalCompactButtons provides true) { actions() }
                        }
                        note?.let { Text(it, color = accent, fontSize = 13.sp) }
                        val line =
                            listOfNotNull(
                                // Guide data tags recordings "Episode" / "Series" / "Movie" as genres: not genres.
                                det.genres.filterNot { it.lowercase() in setOf("episode", "series", "movie", "show") }.takeIf { it.isNotEmpty() }?.let { "Genre: " + it.take(3).joinToString(" / ") },
                                det.studios.firstOrNull()?.let { "Studio: $it" },
                            ).joinToString("    ")
                        if (line.isNotEmpty()) Text(line, color = accent, fontSize = 13.sp)
                        item.overview?.let { Text(it, color = Color(0xFFE6ECF5), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp) }
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            det.video?.let { EmbuaryChip(it) }
                            det.audio.firstOrNull()?.let { EmbuaryChip(it) }
                            commercials?.let { EmbuaryChip(it.label) }
                            det.recordedAt?.let { EmbuaryChip("Recorded " + it.format(DateTimeFormatter.ofPattern("MM/dd"))) }
                            det.location?.let { EmbuaryChip(it) }
                            det.file?.let { EmbuaryChip(it) }
                        }
                    }
                }
                if (episodes.size > 1) {
                    Text("Episodes", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                        items(episodes, key = { it.id.toString() }) { e -> EmbuaryEpisode(e, current = e.id == item.id, accent) { onPlay(PlayRecording(e.id)) } }
                    }
                }
                if (det.cast.isNotEmpty()) {
                    Text("Cast", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(det.cast.take(30), key = { it.id.toString() + it.role }) { p -> CastCard(p, people.card(p.id)) { people.open(p) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbuaryBox(text: String) =
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        modifier =
            Modifier
                .border(1.dp, Color.White)
                .padding(horizontal = 5.dp),
    )

@Composable
private fun EmbuaryChip(text: String) =
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .widthIn(max = 260.dp)
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )

/** One episode in Embuary's row: its picture, a progress line, a tick when watched. */
@Composable
private fun EmbuaryEpisode(
    e: com.mackandall.jdvr.data.LibItem,
    current: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(Modifier.width(172.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.width(172.dp).aspectRatio(16f / 9f),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.ui.graphics.RectangleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1C2A44), focusedContainerColor = Color(0xFF1C2A44)),
            border =
                ClickableSurfaceDefaults.border(
                    border = if (current) Border(BorderStroke(1.dp, accent)) else Border.None,
                    focusedBorder = Border(BorderStroke(3.dp, Color.White)),
                ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = Jdvr.backend.imageUrl(e.id, 240), contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val total = (e.runtimeMin ?: 0L) * 60_000L
                if (!e.played && e.resumeMs > 0 && total > 0) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth((e.resumeMs.toFloat() / total).coerceIn(0.02f, 1f))
                            .height(3.dp)
                            .background(accent),
                    )
                }
                if (e.played) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(listOfNotNull(e.episode?.let { "$it." }, e.name).joinToString(" "), color = if (current) accent else Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
