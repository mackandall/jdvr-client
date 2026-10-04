package com.mackandall.jdvr.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.toUuid
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun isCommercial(s: LiveTvRepository.Segment) = s.type.equals("Commercial", ignoreCase = true)

private fun skippable(s: LiveTvRepository.Segment) = s.type.lowercase() in setOf("commercial", "intro", "recap", "preview")

private fun skipLabel(s: LiveTvRepository.Segment) =
    when (s.type.lowercase()) {
        "commercial" -> "Skip commercial"
        "intro" -> "Skip intro"
        "recap" -> "Skip recap"
        else -> "Skip preview"
    }

/**
 * A recording, full screen: a finished one, a live TV buffer (rewound from the live player), or a
 * programme still recording (Watch from start). Commercials and intros are marked on the bar and
 * skipped per Settings (a Skip prompt, or automatically); a recording still in progress gets the
 * breaks Comskip has found so far, refreshed as it goes.
 *
 * ← / → 10 s back / 30 s ahead · OK or ▼ shows the controls (Skip while the prompt shows) · ▲ back
 * to live (from a buffer) · the remote's media keys work directly · Back leaves. The controls are
 * SageTV's row: previous, rewind, stop, play/pause, fast forward, next. Previous / next are the
 * show's other recordings, or for a live TV buffer the channel's earlier / later programmes (next
 * from the last one is live).
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(
    recording: PlayRecording,
    onExit: () -> Unit,
    onBackToLive: (java.util.UUID) -> Unit,
    onPlayRecording: (PlayRecording) -> Unit,
) {
    val itemId = recording.itemId
    val context = LocalContext.current
    val prefs = Jdvr.prefs
    val scope = rememberCoroutineScope()
    var playback by remember { mutableStateOf<LiveTvRepository.ItemPlayback?>(null) }
    var segments by remember { mutableStateOf<List<LiveTvRepository.Segment>>(emptyList()) }
    var inProgress by remember { mutableStateOf(false) }
    var buffer by remember { mutableStateOf<LiveTvRepository.Buffer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + 5000) }
    var controlsOpen by remember { mutableStateOf(false) }
    var lastKeyAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var previous by remember { mutableStateOf<PlayRecording?>(null) }
    var next by remember { mutableStateOf<PlayRecording?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // Breaks already skipped or skipped past: not skipped again if the viewer goes back into one.
    val handled = remember { mutableSetOf<Long>() }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    val player =
        remember {
            val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(8 * 1024 * 1024)
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Jdvr.http), extractors))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
                .apply { playWhenReady = !recording.paused }
        }

    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    Timber.w(e, "Recording playback error")
                    error = "Playback stopped: ${e.errorCodeName}"
                }
            }
        player.addListener(listener)
        var current: LiveTvRepository.ItemPlayback? = null
        val job =
            MainScope().launch {
                try {
                    val p = Jdvr.liveTv.startItem(itemId)
                    current = p
                    playback = p
                    val start = recording.startMs ?: p.resumeMs
                    player.setMediaItem(MediaItem.fromUri(p.url), start.coerceAtLeast(0))
                    player.prepare()
                    Jdvr.liveTv.reportItem(p, start, paused = recording.paused, event = "start")
                    while (true) {
                        delay(10_000)
                        Jdvr.liveTv.reportItem(p, player.currentPosition, paused = !player.isPlaying, event = "progress")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't play %s", itemId)
                    error = "Couldn't play this recording: ${e.message}"
                }
            }
        onDispose {
            job.cancel()
            val position = player.currentPosition
            player.removeListener(listener)
            player.release()
            // Reported in the background, so leaving the player never waits on the server.
            current?.let { p -> MainScope().launch { runCatching { Jdvr.liveTv.reportItem(p, position, paused = true, event = "stop") } } }
        }
    }

    // Segments: Jellyfin's once a recording is finished; Comskip's live ones (refreshed) while it records.
    LaunchedEffect(itemId) {
        buffer = Jdvr.liveTv.bufferOfItem(itemId)
        while (true) {
            val (live, liveSegs) = Jdvr.liveTv.liveSegments(itemId)
            inProgress = live
            segments = if (live) liveSegs else Jdvr.liveTv.segments(itemId)
            if (!live) break
            delay(15_000)
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (true) {
            delay(250)
            tick++
        }
    }
    // Previous / next: the channel's other buffers, or the show's other recordings in episode order.
    LaunchedEffect(playback?.seriesId) {
        val live = recording.liveChannelId
        if (live != null) {
            val ids = Jdvr.liveTv.buffers(live).mapNotNull { it.itemId?.toUuid() }
            val i = ids.indexOf(itemId)
            if (i >= 0) {
                previous = ids.getOrNull(i - 1)?.let { PlayRecording(it, startMs = 0L, liveChannelId = live) }
                next = ids.getOrNull(i + 1)?.let { PlayRecording(it, startMs = 0L, liveChannelId = live) }
            }
            return@LaunchedEffect
        }
        val series = playback?.seriesId ?: return@LaunchedEffect
        val all = runCatching { Jdvr.liveTv.showRecordings(series, Jdvr.prefs.recordingsFilter) }.getOrNull().orEmpty()
        val ordered =
            if (all.all { it.season != null && it.episode != null }) {
                all.sortedWith(compareBy({ it.season }, { it.episode }))
            } else {
                all.sortedBy { it.recordedUtc ?: "" }
            }
        var ids = ordered.mapNotNull { it.id.toUuid() }
        // Not a live-folder recording (a library or archive episode): the show's own episode order.
        if (itemId !in ids) ids = series.toUuid()?.let { runCatching { Jdvr.liveTv.episodes(it, null) }.getOrNull() }?.map { it.id }.orEmpty()
        val i = ids.indexOf(itemId)
        if (i >= 0) {
            previous = ids.getOrNull(i - 1)?.let { PlayRecording(it) }
            next = ids.getOrNull(i + 1)?.let { PlayRecording(it) }
        }
    }

    LaunchedEffect(controlsOpen) {
        if (controlsOpen) {
            delay(50)
            runCatching { playFocus.requestFocus() }
        } else {
            osdUntil = System.currentTimeMillis() + 2000
            runCatching { focus.requestFocus() }
        }
    }
    // The controls tuck away after 8 s untouched while playing.
    LaunchedEffect(controlsOpen, lastKeyAt) {
        if (!controlsOpen) return@LaunchedEffect
        while (true) {
            delay(1000)
            if (player.isPlaying && System.currentTimeMillis() - lastKeyAt > 8000) {
                controlsOpen = false
                break
            }
        }
    }

    val position = tick.let { player.currentPosition }
    val active = segments.firstOrNull { skippable(it) && position >= it.startMs && position < it.endMs - 1000 }
    val mode = active?.let { if (isCommercial(it)) prefs.commercialSkip else prefs.introSkip } ?: Prefs.OFF
    // Automatic: skip once per break.
    LaunchedEffect(active?.startMs, mode) {
        val a = active ?: return@LaunchedEffect
        if (mode == Prefs.AUTO && a.startMs !in handled) {
            handled += a.startMs
            player.seekTo(a.endMs)
            note = "Skipped " + (if (isCommercial(a)) "a commercial break" else a.type.lowercase())
            delay(3000)
            note = null
        }
    }
    val prompt = active?.takeIf { mode == Prefs.ASK && it.startMs !in handled }

    if (controlsOpen) BackHandler { controlsOpen = false }

    fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
        osdUntil = System.currentTimeMillis() + 5000
    }

    fun seek(forward: Boolean) {
        if (forward) player.seekForward() else player.seekBack()
        osdUntil = System.currentTimeMillis() + 4000
    }

    // Stop: from a live TV buffer, done with the channel too, so its tuner input is freed now.
    fun stop() {
        recording.liveChannelId?.let { Jdvr.liveTv.stopBuffering(it) }
        onExit()
    }

    fun toggleKeep() {
        val b = buffer ?: return
        scope.launch {
            Jdvr.liveTv.keepBuffer(b.id, !b.kept)?.let { buffer = it }
            note = if (b.kept) "Won't keep this buffer" else "Recording kept"
            delay(3000)
            note = null
        }
    }

    fun goNext() {
        val n = next
        val live = recording.liveChannelId
        when {
            n != null -> onPlayRecording(n)
            live != null -> onBackToLive(live)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                lastKeyAt = System.currentTimeMillis()
                if (e.type != KeyEventType.KeyDown || error != null) return@onPreviewKeyEvent false
                // The remote's media keys work whether or not the controls are showing.
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playPause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (!player.isPlaying) playPause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (player.isPlaying) playPause()
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seek(false)
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(true)
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> stop()
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous?.let(onPlayRecording)
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> goNext()
                    AndroidKeyEvent.KEYCODE_MEDIA_RECORD -> toggleKeep()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || error != null || controlsOpen) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        seek(false)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seek(true)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        val p = prompt
                        if (p != null) {
                            handled += p.startMs
                            player.seekTo(p.endMs)
                            osdUntil = System.currentTimeMillis() + 3000
                        } else {
                            controlsOpen = true
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> {
                        controlsOpen = true
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                        val live = recording.liveChannelId
                        if (live != null) onBackToLive(live) else osdUntil = System.currentTimeMillis() + 5000
                        true
                    }
                    AndroidKeyEvent.KEYCODE_INFO -> {
                        osdUntil = System.currentTimeMillis() + 5000
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } }, modifier = Modifier.fillMaxSize())

        prompt?.let {
            Text(
                skipLabel(it) + "  ›  OK",
                color = Color(0xFF06121F),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Jc.Focus, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        note?.let {
            Text(it, color = Jc.Text, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xCC0D1117), RoundedCornerShape(10.dp)).padding(14.dp))
        }

        if (System.currentTimeMillis() < osdUntil || !player.isPlaying || controlsOpen) {
            val duration = player.duration.takeIf { it > 0 } ?: playback?.durationMs ?: 0L
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xF00D1117)).padding(horizontal = 36.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(playback?.name ?: "", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    if (inProgress) Text("● still recording", color = Jc.Recording, fontSize = 15.sp)
                    buffer?.let { Text(if (it.kept) "Live TV buffer · kept" else "Live TV buffer", color = Jc.Muted, fontSize = 15.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(clock(position), color = Jc.Muted, fontSize = 14.sp)
                    SegmentBar(position, duration, segments, Modifier.weight(1f))
                    Text(clock(duration), color = Jc.Muted, fontSize = 14.sp)
                }
                if (controlsOpen) {
                    val live = recording.liveChannelId
                    TransportBar(
                        previous = TransportAction(TransportIcon.Previous, previous != null) { previous?.let(onPlayRecording) },
                        rewind = TransportAction(TransportIcon.Rewind) { seek(false) },
                        stop = TransportAction(TransportIcon.Stop) { stop() },
                        playPause = TransportAction(if (player.isPlaying) TransportIcon.Pause else TransportIcon.Play) { playPause() },
                        forward = TransportAction(TransportIcon.Forward) { seek(true) },
                        next = TransportAction(TransportIcon.Next, next != null || live != null) { goNext() },
                        playFocus = playFocus,
                        // ● on a live TV buffer keeps it as a recording (or lets it go again).
                        record = TransportAction(TransportIcon.Record, enabled = buffer != null, active = buffer?.kept == true) { toggleKeep() },
                    ) {
                        if (live != null) Button("Back to live") { onBackToLive(live) }
                    }
                } else {
                    Text(
                        (if (player.isPlaying) "" else "Paused · ") + "← 10 s · → 30 s · OK controls" + (if (recording.liveChannelId != null) " · ▲ back to live" else "") + " · Back leave",
                        color = Jc.Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        error?.let {
            Column(Modifier.align(Alignment.Center).background(Jc.Panel, RoundedCornerShape(14.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(it, color = Jc.Amber, fontSize = 18.sp)
                Button("Back", primary = true, onClick = onExit)
            }
        }
    }
}

/** The progress bar with commercials (amber) and intros / recaps / previews (purple) marked. */
@Composable
private fun SegmentBar(
    position: Long,
    duration: Long,
    segments: List<LiveTvRepository.Segment>,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.height(10.dp)) {
        val w = maxWidth
        Box(Modifier.fillMaxSize().padding(vertical = 2.dp).background(Jc.Line, RoundedCornerShape(3.dp)))
        if (duration > 0) {
            segments.forEach { s ->
                val from = (s.startMs.toFloat() / duration).coerceIn(0f, 1f)
                val to = (s.endMs.toFloat() / duration).coerceIn(0f, 1f)
                if (to > from) {
                    Box(
                        Modifier
                            .offset(x = w * from)
                            .width(w * (to - from))
                            .fillMaxHeight()
                            .background(if (isCommercial(s)) Jc.Amber else Color(0xFF9B7BFF)),
                    )
                }
            }
            val f = (position.toFloat() / duration).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).padding(vertical = 3.dp).background(Jc.Focus.copy(alpha = 0.85f), RoundedCornerShape(3.dp)))
        }
    }
}
