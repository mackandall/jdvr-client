package com.mackandall.jdvr.player

import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.TunerStatus
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.time
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.delay
import java.util.UUID

/** What picking a channel for the second picture costs. */
private data class PictureCost(
    val text: String,
    val color: Color,
    val enabled: Boolean,
)

/**
 * "Add a picture": the channels, each with what it costs before it's picked. A channel that's
 * recording plays from its recording (no extra tuner); any other needs a tuner of its own.
 */
@Composable
internal fun PicturePicker(
    channels: List<Channel>,
    mainChannelId: UUID,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Multiview: every channel already on screen, and its own title and limit line.
    shown: Set<UUID> = emptySet(),
    title: String = "Add a picture",
    limit: String? = null,
) {
    BackHandler { onClose() }
    var tuners by remember { mutableStateOf<TunerStatus?>(null) }
    var buffers by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Fresh from the server: the channel list's flags can be minutes old, and miss manual recordings.
    var recordingNow by remember { mutableStateOf<Set<UUID>?>(null) }
    LaunchedEffect(Unit) {
        recordingNow = attempt { Jdvr.backend.channelsRecording() }
        tuners = attempt { Jdvr.backend.tuners(null) }
        buffers = attempt { Jdvr.backend.bufferPrograms() }.orEmpty().map { it.replace("-", "").lowercase() }.toSet()
    }
    val busy = tuners?.busy?.size
    val capacity = tuners?.capacity?.takeIf { it > 0 }
    val full = tuners?.full == true
    fun cost(c: Channel): PictureCost {
        val now = c.now
        val recording = recordingNow?.contains(c.id) ?: (now != null && now.isRecording && now.isOn() && now.id.toString().replace("-", "").lowercase() !in buffers)
        return when {
            c.id == mainChannelId && shown.isEmpty() -> PictureCost("Playing in the big picture", Jc.Muted, false)
            c.id in shown -> PictureCost("On screen", Jc.Muted, false)
            recording -> PictureCost("Recording now — no extra tuner, a few seconds behind live", Jc.Green, true)
            full -> PictureCost("All tuners are busy", Jc.Recording, false)
            busy != null && capacity != null -> PictureCost("Uses a tuner (${busy + 1} of $capacity)", Jc.Text, true)
            else -> PictureCost("Uses a tuner of its own", Jc.Text, true)
        }
    }

    val start = channels.indexOfFirst { it.id == mainChannelId }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        first.tryFocus()
    }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(440.dp)
                .fillMaxHeight()
                .background(Jc.Panel.copy(alpha = 0.97f))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Jc.Title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            val budget =
                listOfNotNull(
                    if (busy != null && capacity != null) "Tuners: $busy of $capacity in use" else null,
                    limit ?: "this TV plays up to ${LivePictures.decoders.coerceAtMost(4)} pictures",
                ).joinToString(" · ")
            Text(budget, color = Jc.Muted, fontSize = 13.sp)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                    val k = cost(c)
                    Surface(
                        onClick = { if (k.enabled) onPick(c) },
                        modifier = Modifier.fillMaxWidth().let { if (i == (start + 1).coerceAtMost(channels.lastIndex)) it.focusRequester(first) else it },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors =
                            ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Jc.FocusFill,
                                contentColor = Jc.Text,
                                focusedContentColor = Jc.Text,
                            ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.number, color = Jc.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(c.name, c.now?.name).joinToString(" · "), color = Jc.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(k.text, color = k.color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Text("Pictures with a tuner of their own give way when a recording needs it.", color = Jc.Muted, fontSize = 12.sp)
        }
    }
}

/** Where the small picture sits, and how wide (Settings › Picture in picture). */
@Composable
private fun BoxScope.cornerModifier(barUp: Boolean): Modifier {
    val align =
        when (Jdvr.prefs.pipCorner) {
            "tr" -> Alignment.TopEnd
            "tl" -> Alignment.TopStart
            "bl" -> Alignment.BottomStart
            else -> Alignment.BottomEnd
        }
    val width =
        when (Jdvr.prefs.pipSize) {
            Prefs.SMALL -> 220.dp
            Prefs.LARGE -> 360.dp
            else -> 290.dp
        }
    // Clear of the info bars while they show: above the bottom one (with its buttons), below the top card.
    val top = align == Alignment.TopEnd || align == Alignment.TopStart
    val clear by androidx.compose.animation.core.animateDpAsState(if (!barUp) 30.dp else if (top) 136.dp else 222.dp, label = "pip")
    return Modifier.align(align).padding(start = 28.dp, end = 28.dp, top = if (top) clear else 30.dp, bottom = if (top) 30.dp else clear).width(width)
}

private val onTop get() = Jdvr.prefs.pipCorner.startsWith("t")

/**
 * The small picture over live TV: the second channel (or, swapped, the main one), with what it is
 * and where it comes from; and, beside it, the questions it may ask (the recording under it
 * stopped; a recording needs its tuner).
 */
@Composable
internal fun BoxScope.PictureInPicture(
    main: MainPicture,
    extra: ExtraPicture,
    swapped: Boolean,
    mainChannel: Channel?,
    // The info bars show: the picture moves clear of them.
    barUp: Boolean,
    onFailed: (String) -> Unit,
) {
    LaunchedEffect(extra.state) {
        if (extra.state == PictureState.FAILED) onFailed(extra.message ?: "The picture couldn't play.")
    }
    Column(cornerModifier(barUp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!onTop) Prompts(extra)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .pictureBorder(RoundedCornerShape(8.dp)),
        ) {
            val shown = if (swapped) main.player else extra.player
            LivePictureView(shown, Modifier.fillMaxSize())
            // Until the player here draws its first frame (just opened, or just swapped in), the video
            // layer is see-through and showed the big picture behind it: black instead (3 s at most).
            var covered by remember(shown) { mutableStateOf(true) }
            DisposableEffect(shown) {
                val l =
                    object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            covered = false
                        }
                    }
                shown.addListener(l)
                onDispose { shown.removeListener(l) }
            }
            LaunchedEffect(shown) {
                kotlinx.coroutines.delay(3000)
                covered = false
            }
            // The recording is over: no frozen last frame behind the question.
            if (covered || (!swapped && extra.state == PictureState.RECORDING_STOPPED)) Box(Modifier.fillMaxSize().background(Color.Black))
            // Paused (live TV pause) before going small: it stays paused here, holding its place.
            val cornerPaused = (if (swapped) main.stream.pausedAt else extra.transport.pausedAt) != null
            if (cornerPaused) {
                Text(
                    "❚❚",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            val label: String
            var chip: String
            var chipColor: Color
            if (swapped) {
                label = listOfNotNull(mainChannel?.number, mainChannel?.name, mainChannel?.now?.name).joinToString(" · ")
                chip = "LIVE"
                chipColor = Jc.Text
            } else {
                label = listOfNotNull(extra.channel.number, extra.channel.name, extra.channel.now?.name).joinToString(" · ")
                when {
                    extra.state == PictureState.OPENING -> {
                        chip = "Tuning…"
                        chipColor = Jc.Muted
                    }

                    extra.state == PictureState.RECORDING_STOPPED -> {
                        chip = "Recording stopped"
                        chipColor = Jc.Muted
                    }

                    extra.source is PictureSource.Recording -> {
                        chip = "● RECORDING · no tuner"
                        chipColor = Jc.Recording
                    }

                    else -> {
                        chip = "LIVE · own tuner"
                        chipColor = Jc.Text
                    }
                }
            }
            if (cornerPaused) {
                chip = "❚❚ PAUSED · swap back and Play to go on"
                chipColor = Jc.Text
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chip, color = chipColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(
                "muted",
                color = Color.White,
                fontSize = 9.sp,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
            )
        }
        if (onTop) Prompts(extra)
    }
}

/** The second picture's questions, with the remote's focus on them. */
@Composable
private fun Prompts(extra: ExtraPicture) {
    val giveWay = LivePictures.giveWay
    when {
        giveWay != null -> {
            var left by remember(giveWay) { mutableStateOf(((giveWay.closesAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)) }
            LaunchedEffect(giveWay) {
                while (true) {
                    delay(1000)
                    left = ((giveWay.closesAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                }
            }
            Prompt(
                title = "${giveWay.name} records at ${giveWay.start.time()}",
                body = "It needs a tuner and all are busy. The picture (${extra.channel.number} ${extra.channel.name}) closes in 0:${left.toString().padStart(2, '0')}.",
                yes = "Close it now" to { LivePictures.giveWayNow() },
                no = "Don't record ${giveWay.name}" to { LivePictures.skipRecording() },
                onBack = { LivePictures.giveWayNow() },
            )
        }

        extra.state == PictureState.RECORDING_STOPPED -> {
            Prompt(
                title = "The recording on ${extra.channel.number} stopped",
                body = "Keep watching ${extra.channel.name} live? That uses a tuner of its own.",
                yes = "Keep watching live" to { LivePictures.extraLive() },
                no = "Close the picture" to { LivePictures.closeExtra() },
                onBack = { LivePictures.closeExtra() },
            )
        }
    }
}

@Composable
private fun Prompt(
    title: String,
    body: String,
    yes: Pair<String, () -> Unit>,
    no: Pair<String, () -> Unit>,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val first = remember { FocusRequester() }
    LaunchedEffect(title) {
        delay(80)
        first.tryFocus()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Jc.Panel, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Jc.Title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Jc.Text, fontSize = 13.sp)
        Button(yes.first, primary = true, modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = yes.second)
        Button(no.first, modifier = Modifier.fillMaxWidth(), onClick = no.second)
    }
}

/** OK › Picture with a second picture up: what to do with it. */
@Composable
internal fun BoxScope.PictureMenu(
    extra: ExtraPicture,
    swapped: Boolean,
    onChange: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        first.tryFocus()
    }
    Column(
        Modifier
            .align(Alignment.Center)
            .width(340.dp)
            .background(Jc.Panel, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Picture in picture", color = Jc.Title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(extra.channel.number, extra.channel.name, extra.channel.now?.name).joinToString(" · "), color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Button(if (swapped) "Swap back" else "Swap (sound follows the big picture)", primary = true, modifier = Modifier.fillMaxWidth().focusRequester(first)) {
            LivePictures.swap()
            onClose()
        }
        Button("Change its channel", modifier = Modifier.fillMaxWidth()) { onChange() }
        Button("Close the picture", modifier = Modifier.fillMaxWidth()) {
            LivePictures.closeExtra()
            onClose()
        }
    }
}
