package com.mackandall.jdvr.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LivePlayback
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.Marks
import com.mackandall.jdvr.ui.MarkChips
import com.mackandall.jdvr.ui.rememberMarkSymbols
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/** Sparse-PCR recordings needed an 8 MB TS search window to seek (the Wholphin fix). */
private const val TS_SEARCH_BYTES = 8 * 1024 * 1024

/**
 * Full-screen live TV. Up/down (or channel up/down) change channel, digits type a channel number,
 * OK shows the info bar, Back leaves. Before a channel starts, JellyDVR Schedule is asked whether a
 * tuner is free: if not, "All tuners are busy" lists what they're doing, with Watch buttons.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channelId: UUID,
    onExit: () -> Unit,
    onChannelChange: (UUID) -> Unit,
) {
    val context = LocalContext.current
    val symbols = rememberMarkSymbols()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var playback by remember { mutableStateOf<LivePlayback?>(null) }
    var busy by remember { mutableStateOf<LiveTvRepository.TunerStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + 6000) }
    var typed by remember { mutableStateOf("") }
    var typedAt by remember { mutableStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val player =
        remember {
            val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TS_SEARCH_BYTES)
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Jdvr.http), extractors))
                .build()
                .apply { playWhenReady = true }
        }

    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    Timber.w(e, "Playback error")
                    error = "Playback stopped: ${e.errorCodeName}"
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { channels = Jdvr.liveTv.channels() }
        focus.requestFocus()
    }

    // Start the channel: tuner check, open the stream, play, tell Jellyfin; stop it when leaving.
    DisposableEffect(channelId) {
        var current: LivePlayback? = null
        val job =
            kotlinx.coroutines.MainScope().launch {
                error = null
                busy = null
                osdUntil = System.currentTimeMillis() + 6000
                try {
                    val tuners = Jdvr.liveTv.tuners(channelId)
                    if (tuners != null && tuners.isChannel && tuners.full) {
                        busy = tuners
                        return@launch
                    }
                    val p = Jdvr.liveTv.startLive(channelId)
                    current = p
                    playback = p
                    Jdvr.session.lastChannelId = channelId.toString()
                    player.setMediaItem(MediaItem.fromUri(p.url))
                    player.prepare()
                    Jdvr.liveTv.reportStart(p)
                    while (true) {
                        delay(10_000)
                        Jdvr.liveTv.reportProgress(p, paused = !player.isPlaying)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't start channel %s", channelId)
                    error = "Couldn't start this channel: ${e.message}"
                }
            }
        onDispose {
            job.cancel()
            player.stop()
            current?.let { p ->
                // Closing the live stream frees the tuner: done even as the screen goes away.
                runBlocking { runCatching { Jdvr.liveTv.reportStopped(p) } }
            }
        }
    }

    // A typed channel number goes after 2 s without another digit.
    LaunchedEffect(typedAt) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(2000)
        channels.firstOrNull { it.number == typed }?.let { onChannelChange(it.id) }
        typed = ""
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    fun step(delta: Int) {
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.id == channelId }
        val next = channels[((if (i < 0) 0 else i) + delta).mod(channels.size)]
        onChannelChange(next.id)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                // A dialog (tuners busy, error) has the remote: its buttons, not channel changes.
                if (e.type != KeyEventType.KeyDown || busy != null || error != null) return@onKeyEvent false
                when (val code = e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                        step(1)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        step(-1)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_INFO -> {
                        if (typed.isNotEmpty()) {
                            channels.firstOrNull { it.number == typed }?.let { onChannelChange(it.id) }
                            typed = ""
                        } else {
                            osdUntil = System.currentTimeMillis() + 6000
                        }
                        true
                    }
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        typed = (typed + (code - AndroidKeyEvent.KEYCODE_0)).takeLast(4)
                        typedAt = System.currentTimeMillis()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )

        val channel = channels.firstOrNull { it.id == channelId }
        if (System.currentTimeMillis() < osdUntil || error != null) {
            tick.let { InfoBar(channel, symbols, Modifier.align(Alignment.BottomCenter)) }
        }
        if (typed.isNotEmpty()) {
            Text(
                typed,
                color = Jc.Text,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xCC0D1117), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).background(Jc.Panel, RoundedCornerShape(14.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(it, color = Jc.Amber, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Try again", primary = true) { onChannelChange(channelId) }
                    Button("Back", onClick = onExit)
                }
            }
        }
        busy?.let { status ->
            TunersBusy(status, channel, onWatch = { onChannelChange(it) }, onCancel = onExit, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun InfoBar(
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
    modifier: Modifier,
) {
    val now = channel?.now
    val marks = Marks.parse(now?.rawEpisodeTitle, symbols)
    Column(
        modifier.fillMaxWidth().background(Color(0xF00D1117)).padding(horizontal = 36.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                listOfNotNull(channel?.number, channel?.name).joinToString("  "),
                color = Jc.Muted,
                fontSize = 18.sp,
            )
            Text(now?.name ?: "", color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            MarkChips(marks)
        }
        val sub = listOfNotNull(now?.seasonEpisode, marks.title).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = Jc.Text, fontSize = 16.sp)
        if (now != null) {
            val total = Duration.between(now.start, now.end).toMinutes().coerceAtLeast(1)
            val done = Duration.between(now.start, LocalDateTime.now()).toMinutes().coerceIn(0, total)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(now.start.time(), color = Jc.Muted, fontSize = 14.sp)
                Box(Modifier.width(520.dp).height(6.dp).background(Jc.Line, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total).background(Jc.Focus, RoundedCornerShape(3.dp)))
                }
                Text(now.end.time(), color = Jc.Muted, fontSize = 14.sp)
                if (now.isRecording) Text("● Recording", color = Jc.Recording, fontSize = 14.sp)
            }
        }
        Text("▲▼ change channel · 0–9 type a number · OK info · Back leave", color = Jc.Muted, fontSize = 13.sp)
    }
}

/** Every tuner input is busy: what each is doing, and Watch for the ones that share an input. */
@Composable
private fun TunersBusy(
    status: LiveTvRepository.TunerStatus,
    channel: Channel?,
    onWatch: (UUID) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier.width(640.dp).background(Jc.Panel, RoundedCornerShape(16.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("All tuners are busy", color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${status.requestedChannel ?: channel?.name ?: "This channel"} can't start: all ${status.capacity} tuner inputs are in use. " +
                "Watch one of these (no extra tuner needed), or try again when one frees up.",
            color = Jc.Muted,
            fontSize = 16.sp,
        )
        status.busy.forEachIndexed { i, b ->
            Row(
                Modifier.fillMaxWidth().background(Jc.Panel2, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${b.number} ${b.channelName}".trim(), color = Jc.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    val until =
                        b.endsUtc?.let {
                            runCatching { " until " + LocalDateTime.ofInstant(Instant.parse(it), ZoneId.systemDefault()).time() }.getOrDefault("")
                        } ?: ""
                    val what =
                        listOfNotNull(
                            b.recording.takeIf { it.isNotEmpty() }?.let { "Recording " + it.joinToString(", ") + until },
                            b.watching.takeIf { it.isNotEmpty() }?.let { "Watched on " + it.joinToString(", ") },
                        ).joinToString(" · ").ifEmpty { "In use" }
                    Text(what, color = Jc.Muted, fontSize = 14.sp)
                }
                val id = b.channelId.toUUIDOrNull()
                if (id != null) {
                    Button("Watch", primary = i == 0, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onWatch(id) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button("Cancel", onClick = onCancel)
        }
    }
}
