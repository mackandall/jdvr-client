package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Person
import com.mackandall.jdvr.data.PersonDetails
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.attemptResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.UUID

private val LifeFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy")

/**
 * The cast card that opened a person's page: it takes focus again when the page closes (Back),
 * as the web client's back button returns to the same spot.
 */
class PersonReturn {
    var opened by mutableStateOf<Person?>(null)
    var cameFrom by mutableStateOf<UUID?>(null)
    val focus = FocusRequester()

    fun open(p: Person) {
        cameFrom = p.id
        opened = p
    }

    /** The card's modifier: its focus handle when it's the one the page was opened from. */
    fun card(id: UUID): Modifier = if (id == cameFrom) Modifier.focusRequester(focus) else Modifier
}

@Composable
fun rememberPersonReturn(): PersonReturn {
    val r = remember { PersonReturn() }
    LaunchedEffect(r.opened) {
        if (r.opened != null || r.cameFrom == null) return@LaunchedEffect
        repeat(15) {
            delay(60)
            if (r.focus.tryFocus()) return@LaunchedEffect
        }
    }
    return r
}

/**
 * A cast or crew member, as the web client's person page: photo, born / died / birthplace, the
 * biography, then what they're in across the libraries (movies, shows, episodes). OK on one opens
 * it (its details, or the show's episodes); Back returns here.
 */
@Composable
fun PersonPage(
    person: Person,
    onPlay: (PlayRecording) -> Unit,
) {
    var details by remember(person.id) { mutableStateOf<PersonDetails?>(null) }
    var credits by remember(person.id) { mutableStateOf<List<LibItem>?>(null) }
    var error by remember(person.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(person.id) {
        attemptResult { Jdvr.backend.person(person.id) }.onSuccess { details = it }.onFailure { error = it.message }
        credits = attempt { Jdvr.backend.personCredits(person.id) } ?: emptyList()
    }
    // What was opened from here, and the poster to focus again after Back.
    var movie by remember(person.id) { mutableStateOf<LibItem?>(null) }
    var show by remember(person.id) { mutableStateOf<LibItem?>(null) }
    var cameFrom by remember(person.id) { mutableStateOf<UUID?>(null) }
    val back = remember { FocusRequester() }
    val first = remember { FocusRequester() }

    movie?.let { m ->
        BackHandler { movie = null }
        DetailsPage(m.id, 0, onPlay, actions = { det, say ->
            RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) { movie = null }
        })
        return
    }
    show?.let { s ->
        BackHandler { show = null }
        ShowPage(s, 0, onPlay)
        return
    }
    LaunchedEffect(cameFrom, credits != null) {
        if (credits == null) return@LaunchedEffect
        repeat(15) {
            if ((if (cameFrom != null) back else first).tryFocus()) return@LaunchedEffect
            delay(60)
        }
    }

    val open: (LibItem) -> Unit = { item ->
        cameFrom = item.id
        if (item.type == "Series") show = item else movie = item
    }
    val d = details
    val list = credits.orEmpty()
    val movies = list.filter { it.type == "Movie" || it.type == "Video" }
    val shows = list.filter { it.type == "Series" }
    val episodes = list.filter { it.type == "Episode" }
    // Opening, focus goes to the first thing they're in.
    val firstId = (movies + shows + episodes).firstOrNull()?.id
    var expanded by remember(person.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize().screenBackground()) {
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 36.dp, top = 28.dp, end = 36.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Jc.Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (person.hasImage || d?.hasImage == true) {
                            AsyncImage(model = Jdvr.backend.personImageUrl(person.id), contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Text(initials(person.name), color = Jc.Muted, fontSize = 34.sp)
                        }
                    }
                    Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(d?.name?.ifEmpty { null } ?: person.name, color = Jc.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        d?.let { lifeLines(it) }?.forEach { Text(it, color = Jc.Muted, fontSize = 15.sp) }
                        when {
                            d == null && error == null -> Text("Loading…", color = Jc.Muted, fontSize = 15.sp)
                            error != null -> Text("Couldn't load this person: $error", color = Jc.Amber, fontSize = 15.sp)
                            d?.overview == null -> Text("No biography yet. Jellyfin fills it in from TMDB when it refreshes people.", color = Jc.Muted, fontSize = 15.sp)
                            else -> {
                                Text(d.overview, color = Jc.Text, fontSize = 15.sp, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                                // Long biographies: the whole of it on OK (a focusable button, so the remote can reach it).
                                if (d.overview.length > 420) {
                                    Button(if (expanded) "Show less" else "Read more", compact = true, modifier = if (list.isEmpty()) Modifier.focusRequester(first) else Modifier) { expanded = !expanded }
                                }
                            }
                        }
                    }
                }
                when {
                    credits == null -> Text("Looking for them in your libraries…", color = Jc.Muted, fontSize = 15.sp)
                    list.isEmpty() -> Text("Nothing with them in your libraries.", color = Jc.Muted, fontSize = 15.sp)
                }
                @Composable
                fun posters(
                    title: String,
                    items: List<LibItem>,
                ) {
                    if (items.isEmpty()) return
                    Text("$title · ${items.size}", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                        items(items, key = { it.id }) { item ->
                            val mod =
                                when {
                                    item.id == cameFrom -> Modifier.focusRequester(back)
                                    item.id == firstId && cameFrom == null -> Modifier.focusRequester(first)
                                    else -> Modifier
                                }
                            Box(Modifier.width(120.dp)) { Poster(item, mod) { open(item) } }
                        }
                    }
                }
                posters("Movies", movies)
                posters("Shows", shows)
                if (episodes.isNotEmpty()) {
                    Text("Episodes · ${episodes.size}", color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                        items(episodes, key = { it.id }) { e ->
                            val mod =
                                when {
                                    e.id == cameFrom -> Modifier.focusRequester(back)
                                    e.id == firstId && cameFrom == null -> Modifier.focusRequester(first)
                                    else -> Modifier
                                }
                            EpisodeCard(e, mod) { open(e) }
                        }
                    }
                }
            }
        }
    }
}

private fun initials(name: String) =
    name
        .split(' ')
        .mapNotNull { it.firstOrNull()?.toString() }
        .take(2)
        .joinToString("")

/** "Born March 3, 1955 in Lufkin, Texas, USA", and "Died … (aged 72)" when there's a death date. */
private fun lifeLines(d: PersonDetails): List<String> {
    val born =
        listOfNotNull(
            d.born?.let { "Born ${it.format(LifeFormat)}" },
            d.birthplace?.let { if (d.born != null) "in $it" else "Born in $it" },
        ).joinToString(" ").ifEmpty { null }
    val age = d.born?.let { b -> Period.between(b, d.died ?: java.time.LocalDate.now()).years }
    val died = d.died?.let { "Died ${it.format(LifeFormat)}" + (age?.let { a -> " (aged $a)" } ?: "") }
    val aged = if (d.died == null && age != null) "Age $age" else null
    return listOfNotNull(born, died, aged)
}

/** An episode they're in: its picture, the show and S/E, the episode's name. */
@Composable
private fun EpisodeCard(
    e: LibItem,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(124.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            AsyncImage(model = Jdvr.backend.imageUrl(e.id, 360), contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(listOfNotNull(e.seriesName, e.seasonEpisode).joinToString(" · "), color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(e.name, color = Jc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
