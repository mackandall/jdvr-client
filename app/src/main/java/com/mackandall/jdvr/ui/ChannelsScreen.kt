package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/** Episode History's mark symbols, fetched once (null without the plugin). */
@Composable
fun rememberMarkSymbols(): LiveTvRepository.MarkSymbols? {
    val symbols by produceState<LiveTvRepository.MarkSymbols?>(null) { value = Jdvr.liveTv.markSymbols() }
    return symbols
}

/** Live TV: every channel with what's on now; select one to watch. */
@Composable
fun ChannelsScreen(onPlay: (UUID) -> Unit) {
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val symbols = rememberMarkSymbols()

    LaunchedEffect(Unit) {
        while (true) {
            try {
                channels = Jdvr.liveTv.channels()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Loading channels failed")
                error = "Couldn't load channels: ${e.message}"
            }
            delay(60_000) // what's on changes every half hour or so
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Text("Live TV", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(12)
        error?.let { Text(it, color = Jc.Amber, fontSize = 16.sp) }
        val list = channels
        if (list == null) {
            Text("Loading channels…", color = Jc.Muted, fontSize = 16.sp)
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(list, key = { it.id }) { ch -> ChannelRow(ch, symbols) { onPlay(ch.id) } }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    symbols: LiveTvRepository.MarkSymbols?,
    onClick: () -> Unit,
) {
    val now = ch.now
    val marks = Marks.parse(now?.rawEpisodeTitle, symbols)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Jc.Panel,
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ch.number, color = Jc.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
            Box(Modifier.width(92.dp).fillMaxHeight().padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (ch.logoUrl != null) {
                    AsyncImage(model = ch.logoUrl, contentDescription = ch.name, modifier = Modifier.height(44.dp).width(80.dp))
                } else {
                    Text(ch.name, color = Jc.Muted, fontSize = 13.sp, maxLines = 2)
                }
            }
            Text(ch.name, color = Jc.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (now?.isRecording == true) Box(Modifier.size(10.dp).background(Jc.Recording, CircleShape))
                    Text(now?.name ?: "No guide data", color = Jc.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MarkChips(marks)
                    val sub = listOfNotNull(now?.seasonEpisode, marks.title).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (now != null) {
                    val total = Duration.between(now.start, now.end).toMinutes().coerceAtLeast(1)
                    val done = Duration.between(now.start, LocalDateTime.now()).toMinutes().coerceIn(0, total)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Box(Modifier.width(220.dp).height(4.dp).background(Jc.Line, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total).background(Jc.Focus, RoundedCornerShape(2.dp)))
                        }
                        Text("${now.start.time()} – ${now.end.time()}", color = Jc.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Episode History marks as small coloured chips. */
@Composable
fun MarkChips(marks: Marks) {
    if (!marks.any) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (marks.skipped) Chip("skipped", Jc.Amber)
        if (marks.otherAiring) Chip("↪ other airing", Jc.Focus)
        if (marks.inLibrary) Chip("in library", Jc.Green)
        if (marks.watched) Chip("watched", Jc.Green)
        if (marks.recordedBefore) Chip("recorded before", Jc.Muted)
    }
}

@Composable
fun Chip(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        modifier =
            Modifier
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun Spacer(dp: Int) = androidx.compose.foundation.layout.Spacer(Modifier.height(dp.dp))
