package com.mackandall.jdvr.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.data.toUuid
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.Marks
import com.mackandall.jdvr.ui.MarkChips
import com.mackandall.jdvr.ui.rememberMarkSymbols
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/** This visit's buffer: the one recording now (an older, finished one is the wrong place to land). */
private fun List<LiveTvRepository.Buffer>.currentBuffer() = lastOrNull { it.recording && it.itemId != null }

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
    onPlayRecording: (com.mackandall.jdvr.PlayRecording) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var actionsOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var buffers by remember { mutableStateOf<List<LiveTvRepository.Buffer>>(emptyList()) }
    var fromStart by remember { mutableStateOf<UUID?>(null) }
    val playFocus = remember { FocusRequester() }
    var lastKeyAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var pausedAt by remember(channelId) { mutableStateOf<Instant?>(null) }
    // The mini guide over the bottom of the picture: ▼ opens it, Back closes it.
    var miniOpen by remember { mutableStateOf(false) }
    // Between choosing a channel and its first picture: the tuning filler covers the old one.
    var tuning by remember { mutableStateOf(true) }
    // Guide programmes that are only a buffer (their timers look like recordings).
    var bufferIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recordArmedUntil by remember { mutableStateOf(0L) }
    // Hybrid: the channel starts as direct play (quick to tune) while the server starts an HLS copy
    // of the same stream; pause and rewind move over to the HLS copy, where they're instant.
    var onHls by remember(channelId) { mutableStateOf(false) }
    var directStartedAt by remember(channelId) { mutableStateOf(0L) }
    var hlsStartedAt by remember(channelId) { mutableStateOf<Long?>(null) }
    var swapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
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
                    tuning = false
                }

                override fun onRenderedFirstFrame() {
                    tuning = false
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
                tuning = true
                osdUntil = System.currentTimeMillis() + 6000
                try {
                    val tuners = Jdvr.liveTv.tuners(channelId)
                    if (tuners != null && tuners.isChannel && tuners.full) {
                        tuning = false
                        busy = tuners
                        return@launch
                    }
                    val p = Jdvr.liveTv.startLive(channelId)
                    current = p
                    playback = p
                    Jdvr.session.lastChannelId = channelId.toString()
                    Jdvr.session.pushRecentChannel(channelId.toString())
                    player.setMediaItem(MediaItem.fromUri(p.url))
                    player.playWhenReady = true // a new channel plays even if the last was paused
                    player.prepare()
                    directStartedAt = android.os.SystemClock.elapsedRealtime()
                    onHls = p.hlsUrl == p.url
                    p.hlsUrl?.takeIf { !onHls }?.let { hls ->
                        launch {
                            hlsStartedAt = android.os.SystemClock.elapsedRealtime()
                            Jdvr.liveTv.warmHls(hls)
                        }
                    }
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
                // Closing the live stream frees the tuner: finished in the background even as the
                // screen goes away (waiting for it here froze the screen for ~2 s).
                kotlinx.coroutines.MainScope().launch { runCatching { Jdvr.liveTv.reportStopped(p) } }
            }
        }
    }

    // JellyDVR Live's buffer (from tune-in) and, if the programme is a real recording, the recording.
    LaunchedEffect(channelId, actionsOpen) {
        buffers = Jdvr.liveTv.buffers(channelId)
        bufferIds = Jdvr.liveTv.bufferPrograms()
        fromStart = channels.firstOrNull { it.id == channelId }?.now?.takeIf { it.isRecording }?.let { Jdvr.liveTv.recordingOfProgram(it.id) }
    }
    LaunchedEffect(actionsOpen) {
        if (actionsOpen) {
            osdUntil = Long.MAX_VALUE
            delay(50)
            runCatching { playFocus.requestFocus() }
        } else {
            osdUntil = System.currentTimeMillis() + 3000
            runCatching { focus.requestFocus() }
        }
    }
    // The controls tuck away after 8 s untouched.
    LaunchedEffect(actionsOpen, lastKeyAt, pausedAt) {
        if (!actionsOpen || pausedAt != null) return@LaunchedEffect
        delay(8000)
        actionsOpen = false
    }
    if (actionsOpen) androidx.activity.compose.BackHandler { actionsOpen = false }
    if (miniOpen) androidx.activity.compose.BackHandler { miniOpen = false }
    LaunchedEffect(miniOpen) { if (!miniOpen) runCatching { focus.requestFocus() } }

    fun showOsd() {
        osdUntil = System.currentTimeMillis() + 5000
    }

    // Where a moment of the direct stream is in the HLS copy: both began at the live stream's edge
    // (or its start, for a fresh tune), the HLS copy this much later.
    fun hlsPositionOf(directMs: Long): Long = (directMs - ((hlsStartedAt ?: directStartedAt) - directStartedAt)).coerceAtLeast(0)

    val canHls = playback?.hlsUrl != null && hlsStartedAt != null

    fun switchToHls(
        positionMs: Long,
        play: Boolean,
    ) {
        val url = playback?.hlsUrl ?: return
        player.setMediaItem(MediaItem.fromUri(url), positionMs)
        player.playWhenReady = play
        player.prepare()
        onHls = true
    }

    fun rewind() {
        when {
            onHls -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
            canHls -> switchToHls(hlsPositionOf(player.currentPosition - 10_000), play = pausedAt == null)
            else -> {
                scope.launch {
                    note = "Rewind isn't available on this channel."
                    delay(3000)
                    note = null
                }
            }
        }
        showOsd()
    }

    fun forward() {
        if (!onHls) return
        // No further than live.
        val live = player.currentPosition + (player.currentLiveOffset.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L)
        player.seekTo(minOf(player.currentPosition + 30_000, live))
        showOsd()
    }

    // Back to live: the direct stream again (truly live, not the HLS copy's few seconds behind).
    fun toLive() {
        val p = playback ?: return
        if (onHls && p.hlsUrl != p.url) {
            player.setMediaItem(MediaItem.fromUri(p.url))
            player.playWhenReady = true
            player.prepare()
            directStartedAt = android.os.SystemClock.elapsedRealtime()
            onHls = false
        } else {
            player.seekToDefaultPosition()
            player.play()
        }
        pausedAt = null
        showOsd()
    }

    // ● Record the programme on now: keep the buffer (so it's recorded from tune-in), or schedule it
    // if there's no buffer yet. On a programme already recording, a second press stops it.
    fun record() {
        val now = channels.firstOrNull { it.id == channelId }?.now ?: return
        val b = buffers.currentBuffer()
        val realTimer = now.timerId?.takeIf { now.id.toString().plainId() !in bufferIds }
        scope.launch {
            note =
                runCatching {
                    when {
                        b?.kept == true -> {
                            Jdvr.liveTv.keepBuffer(b.id, false)
                            "Won't record ${now.name} (back to a buffer)"
                        }
                        realTimer != null && System.currentTimeMillis() < recordArmedUntil -> {
                            recordArmedUntil = 0L
                            Jdvr.liveTv.cancelRecording(realTimer)
                            "Stopped recording ${now.name}"
                        }
                        realTimer != null -> {
                            recordArmedUntil = System.currentTimeMillis() + 4000
                            "Already recording ${now.name}: press ● again to stop it"
                        }
                        b != null -> {
                            Jdvr.liveTv.keepBuffer(b.id, true)
                            "Recording ${now.name}" + (b.startedUtc?.let { s -> runCatching { " from " + LocalDateTime.ofInstant(Instant.parse(s), ZoneId.systemDefault()).time() + " (when you tuned in)" }.getOrNull() } ?: "")
                        }
                        else -> {
                            Jdvr.liveTv.record(now.id, series = false)
                            "Recording ${now.name}"
                        }
                    }
                }.getOrElse { "Couldn't change the recording: ${it.message}" }
            buffers = Jdvr.liveTv.buffers(channelId)
            bufferIds = Jdvr.liveTv.bufferPrograms()
            runCatching { channels = Jdvr.liveTv.channels() }
            delay(4000)
            note = null
        }
    }

    // Stop: done with this channel, so its tuner input is freed now rather than after the grace.
    fun stop() {
        Jdvr.liveTv.stopBuffering(channelId)
        onExit()
    }

    // Back to where you tuned in: the start of the HLS copy.
    fun backToTuneIn() {
        if (onHls) player.seekTo(0) else if (canHls) switchToHls(0, play = pausedAt == null)
        showOsd()
    }

    // Pause freezes the direct stream at once; once the HLS copy has caught up to that frame (it
    // runs ~10 s behind), it takes over, still paused, so Play is instant however long the pause.
    fun pause() {
        if (pausedAt != null) return
        pausedAt = Instant.now()
        player.pause()
        if (!onHls && canHls) {
            val at = player.currentPosition
            swapJob?.cancel()
            swapJob =
                scope.launch {
                    delay(12_000)
                    if (pausedAt != null && !onHls) switchToHls(hlsPositionOf(at), play = false)
                }
        }
    }

    fun resume() {
        if (pausedAt == null) return
        pausedAt = null
        swapJob?.cancel()
        player.play()
        showOsd()
    }

    fun playPause() = if (pausedAt == null) pause() else resume()

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
            .onPreviewKeyEvent { e ->
                lastKeyAt = System.currentTimeMillis()
                if (e.type != KeyEventType.KeyDown || busy != null || error != null) return@onPreviewKeyEvent false
                // The mini guide uses ⏩ ⏪ to jump two hours.
                if (miniOpen && e.key.nativeKeyCode in setOf(AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_REWIND)) return@onPreviewKeyEvent false
                // The remote's media keys work whether or not the controls are showing.
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playPause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> resume()
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> rewind()
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> forward()
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> toLive()
                    AndroidKeyEvent.KEYCODE_MEDIA_RECORD -> record()
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> backToTuneIn()
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> stop()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .onKeyEvent { e ->
                // A dialog (tuners busy, error) or the actions row has the remote.
                if (e.type != KeyEventType.KeyDown || busy != null || error != null || actionsOpen || miniOpen) return@onKeyEvent false
                when (val code = e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                        step(1)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        step(-1)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_GUIDE -> {
                        miniOpen = true
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        if (typed.isNotEmpty()) {
                            channels.firstOrNull { it.number == typed }?.let { onChannelChange(it.id) }
                            typed = ""
                        } else if (pausedAt != null) {
                            resume()
                        } else {
                            actionsOpen = true
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_INFO -> {
                        osdUntil = System.currentTimeMillis() + 6000
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        rewind()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        forward()
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
            factory = { ctx -> PlayerView(ctx).apply {
                    useController = false
                    // Switching to the HLS copy keeps the last frame up instead of flashing black.
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                } },
            modifier = Modifier.fillMaxSize(),
        )

        val channel = channels.firstOrNull { it.id == channelId }
        if (tuning && error == null && busy == null) TuningFiller(channel)
        if (!miniOpen && (tuning || System.currentTimeMillis() < osdUntil || error != null || actionsOpen || pausedAt != null)) {
            tick.let {
                val offset = if (onHls) player.currentLiveOffset.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L else 0L
                val realRecording = buffers.currentBuffer()?.kept == true || channel?.now?.let { it.timerId != null && it.id.toString().plainId() !in bufferIds } == true
                InfoBar(channel, symbols, Modifier.align(Alignment.BottomCenter), behindMs = if (offset > 15_000) offset else 0L, recording = realRecording) {
                    if (actionsOpen) {
                        val b = buffers.currentBuffer()
                        val behind = onHls
                        TransportBar(
                            previous = TransportAction(TransportIcon.Previous) { backToTuneIn() },
                            rewind = TransportAction(TransportIcon.Rewind) { rewind() },
                            stop = TransportAction(TransportIcon.Stop) { stop() },
                            playPause = TransportAction(if (pausedAt == null) TransportIcon.Pause else TransportIcon.Play) { playPause() },
                            forward = TransportAction(TransportIcon.Forward, behind) { forward() },
                            // Next = back to live.
                            next = TransportAction(TransportIcon.Next, behind) { toLive() },
                            playFocus = playFocus,
                            record =
                                channel?.now?.let { now ->
                                    val recording = b?.kept == true || (now.timerId != null && now.id.toString().plainId() !in bufferIds)
                                    TransportAction(TransportIcon.Record, active = recording) { record() }
                                },
                        ) {
                            fromStart?.let { rec ->
                                Button("Watch from start") { onPlayRecording(com.mackandall.jdvr.PlayRecording(rec, startMs = 0L, liveChannelId = channelId)) }
                            }
                        }
                    }
                }
            }
        }
        pausedAt?.let { at ->
            Text(
                "❚❚  Paused at " + LocalDateTime.ofInstant(at, ZoneId.systemDefault()).time() + "  ·  ▶ or OK to resume",
                color = Jc.Text,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(28.dp).background(Color(0xCC0D1117), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        note?.let {
            Text(it, color = Jc.Text, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(28.dp).background(Color(0xCC0D1117), RoundedCornerShape(10.dp)).padding(14.dp))
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
        if (miniOpen && channels.isNotEmpty()) {
            MiniGuide(
                channels = channels,
                currentChannelId = channelId,
                symbols = symbols,
                onWatch = {
                    miniOpen = false
                    if (it != channelId) onChannelChange(it)
                },
                onClose = { miniOpen = false },
                modifier = Modifier.fillMaxSize(),
                buffers = bufferIds,
                onPlayRecording = {
                    miniOpen = false
                    onPlayRecording(it)
                },
            )
        }
        busy?.let { status ->
            TunersBusy(status, channel, onWatch = { onChannelChange(it) }, onCancel = onExit, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * While a channel tunes: its programme's picture (else the channel logo, else black), dimmed,
 * with "Tuning 229 HGTV…" top right; the info bar along the bottom says what's coming.
 */
@Composable
private fun TuningFiller(channel: Channel?) {
    var artFailed by remember(channel?.now?.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val now = channel?.now
        val art = now?.let { Jdvr.liveTv.imageUrl(it.id, 720) }
        if (art != null && !artFailed) {
            coil3.compose.AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onState = { if (it is coil3.compose.AsyncImagePainter.State.Error) artFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            channel?.logoUrl?.let {
                coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.align(Alignment.Center).padding(bottom = 140.dp).width(260.dp).height(160.dp))
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x73080B10)))
        Row(
            Modifier.align(Alignment.TopEnd).padding(28.dp).background(Color(0xCC0D1117), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner()
            Text("Tuning " + listOfNotNull(channel?.number, channel?.name).joinToString(" ") + "…", color = Jc.Text, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Spinner() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f,
        360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    androidx.compose.foundation.Canvas(Modifier.width(18.dp).height(18.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        drawArc(Jc.Line, 0f, 360f, false, style = stroke)
        drawArc(Jc.Focus, angle, 100f, false, style = stroke)
    }
}

@Composable
private fun InfoBar(
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
    modifier: Modifier,
    behindMs: Long = 0L,
    recording: Boolean = false,
    actions: @Composable () -> Unit = {},
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
            if (behindMs > 0) {
                val sec = behindMs / 1000
                Text(
                    "−" + (if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)) + " behind live",
                    color = Jc.Amber,
                    fontSize = 16.sp,
                )
            } else {
                Text("● LIVE", color = Jc.Recording, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
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
                if (recording) Text("● Recording", color = Jc.Recording, fontSize = 14.sp)
            }
        }
        actions()
        Text("▲ / CH change channel · ▼ mini guide · 0–9 type a number · ← 10 s back · → 30 s ahead · OK controls · Back leave", color = Jc.Muted, fontSize = 13.sp)
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
