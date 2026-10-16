package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Program
import kotlinx.coroutines.delay
import java.util.UUID

/** What was last searched for, so Search opens with it again. */
private object LastSearch {
    var text = ""
}

/**
 * Search: type a name (on-screen keyboard); movies and shows from every library, and what's coming
 * up on the guide, appear as you type. A movie or show opens as in its library; a programme opens
 * the guide's panel (watch, record, favourites).
 */
@Composable
fun SearchScreen(
    entry: FocusRequester,
    refresh: Int,
    onPlayChannel: (UUID) -> Unit,
    onPlay: (PlayRecording) -> Unit,
) {
    var text by remember { mutableStateOf(LastSearch.text) }
    var library by remember { mutableStateOf<List<LiveTvRepository.LibItem>?>(null) }
    var guide by remember { mutableStateOf<List<Program>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var movie by remember { mutableStateOf<LiveTvRepository.LibItem?>(null) }
    var show by remember { mutableStateOf<LiveTvRepository.LibItem?>(null) }
    var program by remember { mutableStateOf<Program?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val channels by produceState<List<Channel>>(emptyList()) { value = runCatching { Jdvr.liveTv.channels() }.getOrDefault(emptyList()) }
    val symbols = rememberMarkSymbols()
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }

    // Searches once typing pauses.
    LaunchedEffect(text, reload) {
        LastSearch.text = text
        val q = text.trim()
        if (q.length < 2) {
            library = null
            guide = null
            return@LaunchedEffect
        }
        delay(400)
        library = runCatching { Jdvr.liveTv.searchLibrary(q) }.getOrDefault(emptyList())
        guide = runCatching { Jdvr.liveTv.searchGuide(q) }.getOrDefault(emptyList())
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(3500)
            note = null
        }
    }

    movie?.let { m ->
        BackHandler { movie = null }
        DetailsPage(m.id, refresh, onPlay, actions = { det, say ->
            RecordingActions(det.item.id, say, manage = det.location == LIVE_FOLDER, canDelete = det.canDelete, place = det.location) { movie = null }
        })
        return
    }
    show?.let { s ->
        BackHandler { show = null }
        ShowPage(s, refresh, onPlay)
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search", color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Jc.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(Jc.Focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // The keyboard's search key: down to the results.
                keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .focusRequester(entry)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // The field keeps ▼ for its cursor; send it on to the results instead.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                focusManager.moveFocus(FocusDirection.Down)
                                true
                            } else {
                                false
                            }
                        }.border(BorderStroke(2.dp, if (fieldFocused) Jc.Focus else Jc.Line), RoundedCornerShape(10.dp))
                        .background(Jc.Panel2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("A movie, show or programme name", color = Jc.Muted, fontSize = 18.sp)
                    inner()
                },
            )
            note?.let { Text(it, color = Jc.Focus, fontSize = 15.sp) }
            val lib = library
            val progs = guide
            when {
                text.trim().length < 2 -> Text("Type at least two letters. Results come from every library and from the guide.", color = Jc.Muted, fontSize = 15.sp)
                lib == null || progs == null -> Text("Searching…", color = Jc.Muted, fontSize = 15.sp)
                lib.isEmpty() && progs.isEmpty() -> Text("Nothing found for \"${text.trim()}\".", color = Jc.Muted, fontSize = 15.sp)
                else ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        if (lib.isNotEmpty()) {
                            item { Heading("In your libraries", lib.size) }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                                    items(lib, key = { it.id }) { it ->
                                        Box(Modifier.width(118.dp)) {
                                            Poster(it, Modifier) { if (it.type == "Series") show = it else movie = it }
                                        }
                                    }
                                }
                            }
                        }
                        if (progs.isNotEmpty()) {
                            item { Heading("On the guide", progs.size) }
                            items(progs, key = { it.id }) { p ->
                                ProgramRow(p, channels.firstOrNull { it.id == p.channelId }, symbols) { program = p }
                            }
                        }
                    }
            }
        }
        // A programme's actions, over the right of the results.
        program?.let { p ->
            BackHandler { program = null }
            Box(Modifier.align(Alignment.CenterEnd).width(PanelWidth).fillMaxHeight().background(Jc.Sheet)) {
                ProgramPanel(
                    program = p,
                    channel = channels.firstOrNull { it.id == p.channelId },
                    symbols = symbols,
                    onWatch = {
                        program = null
                        onPlayChannel(p.channelId)
                    },
                    onPlayItem = {
                        program = null
                        onPlay(it)
                    },
                    onWatchFromStart = { itemId ->
                        program = null
                        onPlay(PlayRecording(itemId, startMs = 0L))
                    },
                    onChanged = { reload++ },
                    onClose = { program = null },
                    onActed = { msg ->
                        note = msg
                        program = null
                        reload++
                    },
                )
            }
        }
    }
}

@Composable
private fun Heading(
    title: String,
    count: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(title, color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("$count", color = Jc.Muted, fontSize = 14.sp)
    }
}

/** One guide programme: name, episode, when and where; a red dot when it's set to record. */
@Composable
private fun ProgramRow(
    p: Program,
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
    onClick: () -> Unit,
) {
    val marks = Marks.parse(p.rawEpisodeTitle, symbols)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.7f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.isRecording) Text("●", color = Jc.Recording, fontSize = 14.sp)
                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val episode = listOfNotNull(p.seasonEpisode, marks.title).joinToString(" · ")
                if (episode.isNotEmpty()) Text(episode, color = Jc.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (p.isOn()) "On now · until ${p.end.time()}" else "${dayWord(p.start)} · ${p.start.time()}", color = if (p.isOn()) Jc.Focus else Jc.Text, fontSize = 14.sp)
                channel?.let { Text("${it.number} ${it.name}", color = Jc.Muted, fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

private fun dayWord(t: java.time.LocalDateTime): String =
    when (t.toLocalDate()) {
        java.time.LocalDate.now() -> "Today"
        java.time.LocalDate.now().plusDays(1) -> "Tomorrow"
        else -> t.day()
    }
