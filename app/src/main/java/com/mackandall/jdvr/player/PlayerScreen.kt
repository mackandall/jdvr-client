package com.mackandall.jdvr.player

import com.mackandall.jdvr.ui.ProgramActionsHost
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.mackandall.jdvr.ui.seeThrough
import com.mackandall.jdvr.ui.Marks
import com.mackandall.jdvr.ui.MarkChips
import com.mackandall.jdvr.ui.rememberMarkSymbols
import com.mackandall.jdvr.ui.time
import com.mackandall.jdvr.ui.day
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    // Try again: a fresh start of the same channel (a new stream from the server).
    var attempt by remember(channelId) { mutableIntStateOf(0) }
    // Reconnecting by itself: a free channel's stream can break or freeze mid-programme (an ad
    // spliced in with a wild timestamp froze the server's transcode, 2026-10-15). Up to three fresh
    // starts in a row, a little further apart each time; the error only shows if they all fail.
    var reconnects by remember(channelId) { mutableIntStateOf(0) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + 6000) }
    var typed by remember { mutableStateOf("") }
    var typedAt by remember { mutableStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val player =
        remember {
            val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TS_SEARCH_BYTES)
            ExoPlayer
                .Builder(context, renderersFactory(context))
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(OkHttpDataSource.Factory(Jdvr.http), extractors)
                        .setLoadErrorHandlingPolicy(NoRetryOnServerError),
                )
                .build()
                .apply {
                    playWhenReady = true
                    applySubtitlePreference(this)
                }
        }

    val formats = rememberPlayingFormats(player)

    /** A fresh start of the channel, by itself; false once the tries are used up (the error then shows). */
    fun reconnect(why: String): Boolean {
        if (reconnects >= MAX_RECONNECTS) return false
        reconnects++
        Timber.i("Reconnecting channel %s (%d of %d): %s", channelId, reconnects, MAX_RECONNECTS, why)
        val tryNo = reconnects
        player.stop()
        error = null
        tuning = true
        note = "Reconnecting… ($tryNo of $MAX_RECONNECTS)"
        scope.launch {
            delay(RECONNECT_DELAYS_MS[tryNo - 1])
            attempt++
        }
        return true
    }

    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    Timber.w(e, "Playback error")
                    if (!reconnect("the stream stopped (${e.errorCodeName})")) {
                        error = liveErrorText(e)
                        tuning = false
                    }
                }

                override fun onRenderedFirstFrame() {
                    tuning = false
                }
            }
        player.addListener(listener)
        val logger = playerLog("live", player).also { player.addListener(it) }
        onDispose {
            player.removeListener(listener)
            player.removeListener(logger)
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { channels = Jdvr.liveTv.channels() }
        focus.requestFocus()
    }

    // Start the channel: tuner check, open the stream, play, tell Jellyfin; stop it when leaving.
    DisposableEffect(channelId, attempt) {
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
                    // Opening takes 10+ s, and Back, Stop or a channel change meanwhile cancels this, but
                    // the server opens the stream anyway: wait for it regardless and close what comes back,
                    // or that tuner input stays taken until Jellyfin restarts (four were, 2026-10-06).
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { Jdvr.liveTv.startLive(channelId) }
                    if (!isActive) {
                        Timber.i("Channel %s opened after its player left; closing it", channelId)
                        p.liveStreamId?.let { id -> kotlinx.coroutines.MainScope().launch { runCatching { Jdvr.liveTv.closeLiveStream(id) } } }
                        return@launch
                    }
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
                    if (attempt == 0 || !reconnect("the server couldn't start it")) {
                        error = "The server couldn't start this channel. It may be off the air, or its tuner may be busy or not working."
                        tuning = false
                    }
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

    // A stream that freezes without an error (no new picture while it should be playing): after
    // 12 s, a fresh start. Paused (live TV pause), tuning or showing an error doesn't count.
    LaunchedEffect(channelId, attempt) {
        var lastPos = -1L
        var stillSince = 0L
        while (true) {
            delay(2000)
            val playing = player.playWhenReady && pausedAt == null && !tuning && error == null && busy == null
            val pos = player.currentPosition
            val now = android.os.SystemClock.elapsedRealtime()
            if (!playing || pos != lastPos) {
                lastPos = pos
                stillSince = now
            } else if (now - stillSince >= STALL_MS) {
                if (!reconnect("no new picture for ${STALL_MS / 1000} s")) {
                    player.stop()
                    error = "The channel stopped sending a picture and reconnecting didn't help. It may be having trouble at the source."
                    tuning = false
                }
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(tuning, attempt) {
        if (!tuning && note?.startsWith("Reconnecting") == true) note = null
    }
    LaunchedEffect(attempt) {
        delay(GOOD_SPELL_MS)
        if (error == null && !tuning) reconnects = 0
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
    // The programme's full panel (Info): details plus Record, series, favourites, watched…
    var infoOpen by remember { mutableStateOf(false) }
    if (infoOpen) androidx.activity.compose.BackHandler { infoOpen = false }
    LaunchedEffect(infoOpen) { if (!infoOpen) runCatching { focus.requestFocus() } }
    // The top info box's extras for the programme on now: ratings, genres, and what's next.
    var nowDetails by remember { mutableStateOf<LiveTvRepository.ItemDetails?>(null) }
    var nextUp by remember { mutableStateOf<com.mackandall.jdvr.data.Program?>(null) }
    val nowId = channels.firstOrNull { it.id == channelId }?.now?.id
    LaunchedEffect(channelId, nowId) {
        nowDetails = null
        nextUp = null
        val now = channels.firstOrNull { it.id == channelId }?.now ?: return@LaunchedEffect
        nowDetails = runCatching { Jdvr.liveTv.details(now.id) }.getOrNull()
        nextUp = runCatching { Jdvr.liveTv.programs(listOf(channelId), now.end, now.end.plusHours(3)) }.getOrNull()?.firstOrNull { !it.start.isBefore(now.end) }
    }
    LaunchedEffect(miniOpen) { if (!miniOpen) runCatching { focus.requestFocus() } }

    fun showOsd() {
        osdUntil = System.currentTimeMillis() + 5000
    }

    // Where a moment of the direct stream is in the HLS copy: both began at the live stream's edge
    // (or its start, for a fresh tune), the HLS copy this much later.
    fun hlsPositionOf(directMs: Long): Long = (directMs - ((hlsStartedAt ?: directStartedAt) - directStartedAt)).coerceAtLeast(0)

    val canHls = playback?.hlsUrl != null && hlsStartedAt != null

    // How far behind live the picture is. Jellyfin's live HLS has no programme date-times, so the
    // player's own live offset is unknown: the copy's end (its duration so far) is live instead.
    fun behindLiveMs(): Long =
        if (onHls) {
            player.currentLiveOffset.takeIf { it != androidx.media3.common.C.TIME_UNSET }
                ?: player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.let { (it - player.currentPosition).coerceAtLeast(0) }
                ?: 0L
        } else {
            // The direct stream paused (before the HLS copy takes over).
            pausedAt?.let { Duration.between(it, Instant.now()).toMillis() } ?: 0L
        }

    // Where rewinding can go back to: the start of the HLS copy (about when you tuned in).
    fun bufferFrom(behind: Long): LocalDateTime? =
        when {
            onHls -> LocalDateTime.now().minusNanos((behind + player.currentPosition) * 1_000_000)
            hlsStartedAt != null -> LocalDateTime.now().minusNanos((android.os.SystemClock.elapsedRealtime() - hlsStartedAt!!) * 1_000_000)
            else -> null
        }

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

    fun forward() {
        if (!onHls) return
        // No further than live: within 30 s of it, back to the live stream itself.
        if (behindLiveMs() <= 30_000) toLive() else player.seekTo(player.currentPosition + 30_000)
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
                if (e.type != KeyEventType.KeyDown || busy != null || error != null || actionsOpen || miniOpen || infoOpen) return@onKeyEvent false
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
                        infoOpen = true
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
        if (!miniOpen && !infoOpen && (tuning || System.currentTimeMillis() < osdUntil || actionsOpen || pausedAt != null) && error == null && busy == null) {
            channel?.let { TopInfoBox(it, symbols, nowDetails, nextUp, Modifier.align(Alignment.TopCenter)) }
        }
        if (!miniOpen && (tuning || System.currentTimeMillis() < osdUntil || error != null || actionsOpen || pausedAt != null)) {
            tick.let {
                val offset = behindLiveMs()
                val realRecording = buffers.currentBuffer()?.kept == true || channel?.now?.let { it.timerId != null && it.id.toString().plainId() !in bufferIds } == true
                InfoBar(channel, symbols, Modifier.align(Alignment.BottomCenter), behindMs = if (offset > 15_000) offset else 0L, recording = realRecording, formats = formats, watchingAt = LocalDateTime.now().minusNanos(offset * 1_000_000), bufferFrom = bufferFrom(offset), pausedAt = pausedAt) {
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
                                channel?.now?.takeIf { com.mackandall.jdvr.ui.DeviceRights.info.record }?.let { now ->
                                    val recording = b?.kept == true || (now.timerId != null && now.id.toString().plainId() !in bufferIds)
                                    TransportAction(TransportIcon.Record, active = recording) { record() }
                                },
                        ) {
                            Button("Info") {
                                actionsOpen = false
                                infoOpen = true
                            }
                            TrackButtons(player)
                            fromStart?.let { rec ->
                                Button("From start") { onPlayRecording(com.mackandall.jdvr.PlayRecording(rec, startMs = 0L, liveChannelId = channelId)) }
                            }
                        }
                    }
                }
            }
        }
        // In the bar when it shows; up here only behind the mini guide.
        pausedAt?.takeIf { miniOpen }?.let { at ->
            Text(
                "❚❚  Paused at " + LocalDateTime.ofInstant(at, ZoneId.systemDefault()).time() + "  ·  ▶ or OK to resume",
                color = Jc.Text,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(28.dp).background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        note?.let {
            Text(it, color = Jc.Text, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopCenter).padding(28.dp).background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(14.dp))
        }
        if (typed.isNotEmpty()) {
            Text(
                typed,
                color = Jc.Text,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        error?.let {
            Column(
                Modifier.align(Alignment.Center).background(Jc.Sheet, RoundedCornerShape(14.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(it, color = Jc.Amber, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Try again", primary = true) {
                        error = null
                        reconnects = 0
                        attempt++
                    }
                    Button("Back", onClick = onExit)
                }
            }
        }
        // Info: the guide's programme panel on the right, with all its options.
        if (infoOpen) {
            channel?.now?.let { now ->
                ProgramActionsHost(onDismiss = { infoOpen = false }) {
                    com.mackandall.jdvr.ui.ProgramPanel(
                        program = now,
                        channel = channel,
                        symbols = symbols,
                        onWatch = { infoOpen = false },
                        onWatchFromStart = { id ->
                            infoOpen = false
                            onPlayRecording(com.mackandall.jdvr.PlayRecording(id, startMs = 0L, liveChannelId = channelId))
                        },
                        onPlayItem = {
                            infoOpen = false
                            onPlayRecording(it)
                        },
                        onChanged = {},
                        onClose = { infoOpen = false },
                        onActed = { msg ->
                            infoOpen = false
                            scope.launch {
                                note = msg
                                delay(1500)
                                runCatching { channels = Jdvr.liveTv.channels() }
                                buffers = Jdvr.liveTv.buffers(channelId)
                                bufferIds = Jdvr.liveTv.bufferPrograms()
                                delay(2500)
                                note = null
                            }
                        },
                        modifier = Modifier.width(400.dp),
                    )
                }
            } ?: run { infoOpen = false }
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
            // Middle right: the top box and the info bar are up while it tunes.
            Modifier.align(Alignment.CenterEnd).padding(28.dp).background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
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

/**
 * SageTV's top box, shown with the controls: the channel (logo, number, name), the programme
 * (title, episode, marks, aired, rating, score, genres), its description and what's on next.
 */
@Composable
private fun TopInfoBox(
    channel: Channel,
    symbols: LiveTvRepository.MarkSymbols?,
    details: LiveTvRepository.ItemDetails?,
    next: com.mackandall.jdvr.data.Program?,
    modifier: Modifier,
) {
    val now = channel.now
    val marks = Marks.parse(now?.rawEpisodeTitle, symbols)
    Row(
        modifier.padding(top = 18.dp).fillMaxWidth(0.76f).background(Jc.Ink.copy(alpha = 0.92f), RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier.width(96.dp).background(Jc.Panel2.seeThrough(0.9f), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            channel.logoUrl?.let { coil3.compose.AsyncImage(model = it, contentDescription = channel.name, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.width(64.dp).height(36.dp)) }
            Text(channel.number, color = Jc.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(channel.name, color = Jc.Muted, fontSize = 11.sp, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(now?.name ?: channel.name, color = Jc.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                listOfNotNull(now?.seasonEpisode, marks.title).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Jc.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                MarkChips(marks)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                now?.let { Text("Aired ${it.start.day()} · ${it.start.time()} – ${it.end.time()}", color = Jc.Text, fontSize = 13.sp) }
                details?.item?.officialRating?.let { Text(it, color = Jc.Text, fontSize = 12.sp, modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp)) }
                details?.communityRating?.let { Text("★ %.1f".format(it), color = Jc.Gold, fontSize = 13.sp) }
                listOfNotNull(details?.genres?.take(3)?.joinToString(" · ")?.ifEmpty { null }, details?.item?.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Jc.Muted, fontSize = 13.sp, maxLines = 1)
                }
            }
            (now?.overview ?: details?.item?.overview)?.let { Text(it, color = Jc.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            next?.let {
                Text(
                    "Next · ${it.start.time()} · ${it.name}" + (Marks.parse(it.rawEpisodeTitle, symbols).title?.let { t -> " · $t" } ?: ""),
                    color = Jc.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun InfoBar(
    channel: Channel?,
    symbols: LiveTvRepository.MarkSymbols?,
    modifier: Modifier,
    behindMs: Long = 0L,
    recording: Boolean = false,
    formats: PlayingFormats? = null,
    // The moment on screen (live, or earlier when rewound), and how far back the buffer goes.
    watchingAt: LocalDateTime = LocalDateTime.now(),
    bufferFrom: LocalDateTime? = null,
    pausedAt: java.time.Instant? = null,
    actions: @Composable () -> Unit = {},
) {
    val now = channel?.now
    val marks = Marks.parse(now?.rawEpisodeTitle, symbols)
    Column(
        modifier.fillMaxWidth().background(Jc.Ink.copy(alpha = 0.94f)).padding(horizontal = 36.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TitleWithFormats(formats, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                listOfNotNull(channel?.number, channel?.name).joinToString("  "),
                color = Jc.Muted,
                fontSize = 18.sp,
                maxLines = 1,
                softWrap = false,
            )
            Text(now?.name ?: "", color = Jc.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            MarkChips(marks)
            if (behindMs > 0) {
                val sec = behindMs / 1000
                Text(
                    "−" + (if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)) + " behind live",
                    color = Jc.Amber,
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                Text("● LIVE", color = Jc.Recording, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        }
        val sub = listOfNotNull(now?.seasonEpisode, marks.title).joinToString(" · ")
        if (sub.isNotEmpty() || pausedAt != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (sub.isNotEmpty()) Text(sub, color = Jc.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                pausedAt?.let { at ->
                    Text(
                        "❚❚  Paused at " + LocalDateTime.ofInstant(at, ZoneId.systemDefault()).time() + "  ·  ▶ or OK to resume",
                        color = Jc.Text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.background(Jc.Panel2, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(now.start.time(), color = Jc.Muted, fontSize = 14.sp)
                LiveTimeline(now.start, now.end, watchingAt, bufferFrom, behindMs, Modifier.width(520.dp))
                Text(now.end.time(), color = Jc.Muted, fontSize = 14.sp)
                if (recording) Text("● Recording", color = Jc.Recording, fontSize = 14.sp)
            }
        }
        actions()
        Text("▲ / CH change channel · ▼ mini guide · 0–9 type a number · ← 10 s back · → 30 s ahead · OK controls · Back leave", color = Jc.Muted, fontSize = 13.sp)
    }
}

/**
 * The programme's bar: what has aired before the buffer starts (faint), the buffer up to the moment
 * on screen (solid). Once you're behind: a thumb where you are, the buffer still ahead of you
 * striped, a red LIVE tag over the live edge, and under the bar a bracket from you to live with
 * how far behind that is.
 */
@Composable
private fun LiveTimeline(
    start: LocalDateTime,
    end: LocalDateTime,
    watchingAt: LocalDateTime,
    bufferFrom: LocalDateTime?,
    // How far behind live, when far enough to say so (the HLS copy always runs a few seconds behind); else 0.
    behindMs: Long,
    modifier: Modifier,
) {
    val total = Duration.between(start, end).seconds.coerceAtLeast(1).toFloat()
    fun at(t: LocalDateTime) = (Duration.between(start, t).seconds / total).coerceIn(0f, 1f)
    val live = at(LocalDateTime.now())
    val watching = at(watchingAt).coerceAtMost(live)
    val from = bufferFrom?.let { at(it) }?.coerceAtMost(watching) ?: 0f
    val behind = behindMs > 0
    val line = Jc.Line
    val focus = Jc.Focus
    val red = Jc.Recording
    val amber = Jc.Amber
    val aired = Jc.Muted.copy(alpha = 0.45f)
    // The bar's middle, with room for the LIVE tag above and the bracket and time below.
    val mid = 30.dp
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.height(if (behind) 62.dp else 22.dp)) {
        val w = maxWidth
        val barMid = if (behind) mid else 11.dp
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val h = 6.dp.toPx()
            val y = barMid.toPx() - h / 2
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            fun seg(a: Float, b: Float, c: Color) {
                if (b > a) drawRoundRect(c, androidx.compose.ui.geometry.Offset(a * size.width, y), androidx.compose.ui.geometry.Size((b - a) * size.width, h), r)
            }
            seg(0f, 1f, line)
            seg(0f, from, aired)
            seg(from, watching, focus)
            if (!behind) return@Canvas
            // The buffer ahead of you: stripes.
            val a = watching * size.width
            val b = live * size.width
            if (b > a) {
                drawRoundRect(focus.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(a, y), androidx.compose.ui.geometry.Size(b - a, h), r)
                val clip = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(a, y, b, y + h, r)) }
                clipPath(clip) {
                    val step = 7.dp.toPx()
                    var x = a - h
                    while (x < b) {
                        drawLine(focus.copy(alpha = 0.75f), androidx.compose.ui.geometry.Offset(x, y + h), androidx.compose.ui.geometry.Offset(x + h, y), strokeWidth = 3.dp.toPx())
                        x += step
                    }
                }
            }
            // Live: a red tick.
            drawRect(red, androidx.compose.ui.geometry.Offset(b - 1.dp.toPx(), barMid.toPx() - 9.dp.toPx()), androidx.compose.ui.geometry.Size(2.dp.toPx(), 18.dp.toPx()))
            // The bracket from you to live, under the bar.
            val by = barMid.toPx() + 12.dp.toPx()
            val bh = 6.dp.toPx()
            val sw = 1.dp.toPx()
            drawLine(amber, androidx.compose.ui.geometry.Offset(a, by), androidx.compose.ui.geometry.Offset(a, by + bh), sw)
            drawLine(amber, androidx.compose.ui.geometry.Offset(a, by + bh), androidx.compose.ui.geometry.Offset(b, by + bh), sw)
            drawLine(amber, androidx.compose.ui.geometry.Offset(b, by), androidx.compose.ui.geometry.Offset(b, by + bh), sw)
            // You: a thumb.
            val tw = 8.dp.toPx()
            val th = 22.dp.toPx()
            val tl = androidx.compose.ui.geometry.Offset(a - tw / 2, barMid.toPx() - th / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.5f), tl + androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()), androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(tw / 2))
            drawRoundRect(Color.White, tl, androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(tw / 2))
            drawRoundRect(focus, tl, androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(tw / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        if (behind) {
            // LIVE over the live edge, and how far behind under the middle of the bracket.
            Box(Modifier.offset(x = w * live - 40.dp, y = 0.dp).width(80.dp), contentAlignment = Alignment.Center) {
                Text("● LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(red, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            val sec = behindMs / 1000
            Box(Modifier.offset(x = w * (watching + live) / 2 - 40.dp, y = mid + 18.dp).width(80.dp), contentAlignment = Alignment.Center) {
                Text(if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60), color = amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
        modifier.width(640.dp).background(Jc.Sheet, RoundedCornerShape(16.dp)).padding(24.dp),
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

/**
 * A live channel's errors: a server error (the stream couldn't be opened or broke off) won't get
 * better by asking again a few seconds later, so the player gives up at once and says so, instead
 * of retrying for half a minute behind the tuning spinner. Other errors keep the usual retries.
 */
private const val MAX_RECONNECTS = 3
private val RECONNECT_DELAYS_MS = longArrayOf(2000, 4000, 8000)
private const val STALL_MS = 12_000L
private const val GOOD_SPELL_MS = 90_000L

private object NoRetryOnServerError : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && e.responseCode >= 500) return androidx.media3.common.C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}

/** What went wrong with a live channel, in words. */
private fun liveErrorText(e: PlaybackException): String =
    when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "The server couldn't start this channel. It may be off the air, or its tuner may be busy or not working."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Lost the connection to the server."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This TV couldn't play this channel's picture or sound."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "The channel's stream came through damaged."
        else -> "Playback stopped (${e.errorCodeName})."
    }
