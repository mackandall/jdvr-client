package com.mackandall.jdvr.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.ItemDetails
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.MinimalScroll
import com.mackandall.jdvr.ui.RecordingActions
import com.mackandall.jdvr.ui.day
import com.mackandall.jdvr.ui.seeThrough
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

// What's playing, for the recording player: a box at the top (like live TV's) and an Info panel
// on the right. Every line is built from what the item has, so a movie without episodes or an
// archive copy without a record date just has fewer lines, never empty ones.

private val AiredFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** "S3:E1 · Into the New World" for an episode, the year for anything else. */
private fun ItemDetails.subtitle(): String? =
    if (item.seriesName != null && item.type == "Episode") {
        listOfNotNull(item.seasonEpisode, item.name).joinToString(" · ")
    } else {
        item.year?.toString()
    }

private fun ItemDetails.title(): String = if (item.type == "Episode") item.seriesName ?: item.name else item.name

private fun length(min: Long): String = if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m" else "${min}m"

/** Recorded · aired · rating · ★ · critics · runtime · genres: whichever it has. */
@Composable
private fun MetaLine(
    d: ItemDetails,
    fontSize: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        d.recordedAt?.let { Text("Recorded ${it.day()} · ${it.time()}", color = Jc.Text, fontSize = fontSize.sp, maxLines = 1, softWrap = false) }
        if (d.item.type == "Episode") d.premiere?.let { Text("Aired ${it.format(AiredFormat)}", color = Jc.Muted, fontSize = fontSize.sp, maxLines = 1, softWrap = false) }
        d.item.officialRating?.let {
            Text(it, color = Jc.Text, fontSize = (fontSize - 1).sp, maxLines = 1, softWrap = false, modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp))
        }
        d.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = fontSize.sp, maxLines = 1, softWrap = false) }
        d.criticRating?.let { Text("${it.toInt()}% critics", color = Jc.Text, fontSize = fontSize.sp, maxLines = 1, softWrap = false) }
        if (d.item.type != "Episode") d.item.runtimeMin?.let { Text(length(it), color = Jc.Text, fontSize = fontSize.sp, maxLines = 1, softWrap = false) }
        d.genres.take(3).joinToString(" · ").ifEmpty { null }?.let {
            Text(it, color = Jc.Muted, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun Poster(
    d: ItemDetails,
    width: Int,
) {
    // An episode shows its show's poster; anything else its own.
    val image = (if (d.item.type == "Episode") d.item.seriesId else null) ?: d.item.id
    Box(
        Modifier
            .width(width.dp)
            .height((width * 1.5f).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Jc.Panel),
    ) {
        AsyncImage(model = Jdvr.backend.imageUrl(image, width * 4), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
    }
}

/**
 * The box at the top while a recording, show or movie plays: what it is, when it was recorded
 * and aired, what it's about, and the next episode (or a movie's tagline).
 */
@Composable
fun RecordingTopBox(
    d: ItemDetails,
    next: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(top = 18.dp)
            .fillMaxWidth(0.76f)
            .clip(RoundedCornerShape(14.dp))
            .background(Jc.Ink.copy(alpha = 0.92f)),
    ) {
        Column(
            Modifier.width(96.dp).background(Jc.Panel2.seeThrough(0.9f)).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Poster(d, 60)
            if (d.item.type == "Episode") d.item.seasonEpisode?.let { Text(it, color = Jc.Muted, fontSize = 11.sp, maxLines = 1) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(d.title(), color = Jc.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                d.subtitle()?.let { Text(it, color = Jc.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            }
            MetaLine(d, 13)
            d.item.overview?.let { Text(it, color = Jc.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            when {
                next != null -> Text("Next · $next", color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                d.item.type != "Episode" -> d.tagline?.let { Text(it, color = Jc.Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * Info while watching: the details page's facts and actions in a panel on the right, the video
 * still playing behind it. Sections it has nothing for are left out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingInfoPanel(
    d: ItemDetails,
    commercials: String?,
    note: String?,
    first: FocusRequester,
    onClose: () -> Unit,
    onNote: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrolls only as far as needed; back to the top on the first button; Down past the buttons
    // focuses the facts and cast below, scrolling them into view.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var moreFocused by remember { mutableStateOf(false) }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        Column(
            modifier
                .fillMaxHeight()
                .fillMaxWidth(0.43f)
                .background(Jc.Ink.copy(alpha = 0.96f))
                .verticalScroll(scroll)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Poster(d, 75)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(d.title(), color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    d.subtitle()?.let { Text(it, color = Jc.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.item.type == "Episode") d.premiere?.let { Text("Aired ${it.format(AiredFormat)}", color = Jc.Muted, fontSize = 13.sp) }
                        d.item.officialRating?.let { Text(it, color = Jc.Text, fontSize = 12.sp, modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp)) }
                        d.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = 13.sp) }
                        d.criticRating?.let { Text("${it.toInt()}% critics", color = Jc.Text, fontSize = 13.sp) }
                        d.item.runtimeMin?.let { Text(length(it), color = Jc.Text, fontSize = 13.sp) }
                    }
                    if (d.genres.isNotEmpty()) Text(d.genres.take(4).joinToString(" · "), color = Jc.Muted, fontSize = 13.sp)
                    d.recordedAt?.let { Text("Recorded ${it.day()} · ${it.time()}", color = Jc.Muted, fontSize = 13.sp) }
                }
            }
            d.tagline?.let { Text(it, color = Jc.Text, fontSize = 14.sp, fontStyle = FontStyle.Italic) }
            d.item.overview?.let { Text(it, color = Jc.Text, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    if (d.item.type == "Episode") {
                        "Back to the episode"
                    } else if (d.item.type == "Movie") {
                        "Back to the movie"
                    } else {
                        "Back to the video"
                    },
                    primary = true,
                    modifier = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) scope.launch { scroll.animateScrollTo(0) } },
                    onClick = onClose,
                )
                RecordingActions(d.item.id, onNote, manage = d.location == com.mackandall.jdvr.ui.LIVE_FOLDER, canDelete = d.canDelete, place = d.location, onDeleted = onDeleted)
            }
            note?.let { Text(it, color = Jc.Focus, fontSize = 13.sp) }
            Column(
                Modifier
                    .onFocusChanged { moreFocused = it.isFocused }
                    .focusable()
                    .border(2.dp, if (moreFocused) Jc.Focus else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.video?.let { Fact("Video", it) }
                    if (d.audio.isNotEmpty()) Fact("Audio", d.audio.first() + if (d.audio.size > 1) "  +${d.audio.size - 1}" else "")
                    d.file?.let { Fact("File", it) }
                    d.location?.let { Fact("Stored", it) }
                    commercials?.let { Fact("Commercials", it) }
                }
                listOfNotNull(
                    d.directors.takeIf { it.isNotEmpty() }?.let { "Directed by " + it.joinToString(", ") { p -> p.name } },
                    d.writers.takeIf { it.isNotEmpty() }?.let { "Written by " + it.take(3).joinToString(", ") { p -> p.name } },
                ).joinToString("  ·  ").ifEmpty { null }?.let { Text(it, color = Jc.Muted, fontSize = 13.sp) }
                if (d.cast.isNotEmpty()) {
                    Text("Cast", color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(d.cast.take(12).joinToString(", ") { p -> p.name + (p.role?.let { " ($it)" } ?: "") }, color = Jc.Muted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column(Modifier.background(Jc.Panel.seeThrough(0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label.uppercase(), color = Jc.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = Jc.Text, fontSize = 13.sp)
    }
}
