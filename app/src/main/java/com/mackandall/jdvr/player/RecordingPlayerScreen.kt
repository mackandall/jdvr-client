package com.mackandall.jdvr.player

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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.mackandall.jdvr.data.Buffer
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.Segment
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.view.KeyEvent as AndroidKeyEvent

private fun isCommercial(s: Segment) = s.type.equals("Commercial", ignoreCase = true)

private fun skippable(s: Segment) = s.type.lowercase() in setOf("commercial", "intro", "recap", "preview")

private fun skipLabel(s: Segment) =
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
    val live = recording.liveChannelId
    val context = LocalContext.current
    val prefs = Jdvr.prefs
    val scope = rememberCoroutineScope()
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + 5000) }
    var controlsOpen by remember { mutableStateOf(false) }
    var lastKeyAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // Breaks already skipped or skipped past: not skipped again if the viewer goes back into one.
    val handled = remember { mutableSetOf<Long>() }
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var infoOpen by remember { mutableStateOf(false) }
    var infoNote by remember { mutableStateOf<String?>(null) }
    val infoFocus = remember { FocusRequester() }
    val player = remember { recordingPlayer(context, paused = recording.paused) }
    val formats = rememberPlayingFormats(player)
    val rec = remember { RecordingSession(recording, player) }
    val editor =
        remember {
            RecordingEditor(
                itemId,
                player,
                scope,
                say = { note = it },
                reloadSegments = { rec.reloadSegments() },
                closeControls = { controlsOpen = false },
                playFocus = playFocus,
            )
        }
    editor.Effects(player.isPlaying)
    val edit =
        com.mackandall.jdvr.ui
            .editPermission()

    fun flash(
        message: String,
        forMs: Long = 3000,
    ) {
        scope.launch {
            note = message
            delay(forMs)
            note = null
        }
    }

    // Play it, report progress, and report where it stopped when leaving.
    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    Timber.w(e, "Recording playback error")
                    rec.error = "Playback stopped: ${e.errorCodeName}"
                }
            }
        player.addListener(listener)
        val logger = playerLog("recording $itemId", player).also { player.addListener(it) }
        val job = rec.start()
        onDispose {
            job.cancel()
            val position = player.currentPosition
            player.removeListener(listener)
            player.removeListener(logger)
            player.release()
            rec.reportStopped(position)
        }
    }

    LaunchedEffect(itemId) { rec.followSegments() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (true) {
            delay(250)
            tick++
        }
    }
    LaunchedEffect(itemId) { rec.loadDetails() }
    LaunchedEffect(rec.playback?.seriesId) { rec.loadNeighbours() }
    LaunchedEffect(infoOpen) {
        if (infoOpen) {
            delay(80)
            infoFocus.tryFocus()
        } else {
            focus.tryFocus()
        }
    }
    LaunchedEffect(controlsOpen) {
        if (controlsOpen) {
            delay(50)
            playFocus.tryFocus()
        } else {
            osdUntil = System.currentTimeMillis() + 2000
            focus.tryFocus()
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
    val active = rec.segments.firstOrNull { skippable(it) && position >= it.startMs && position < it.endMs - 1000 }
    val mode = active?.let { if (isCommercial(it)) prefs.commercialSkip else prefs.introSkip } ?: Prefs.OFF
    // Automatic: skip once per break.
    LaunchedEffect(active?.startMs, mode) {
        val a = active ?: return@LaunchedEffect
        if (mode == Prefs.AUTO && a.startMs !in handled) {
            handled += a.startMs
            player.seekTo(a.endMs)
            flash("Skipped " + (if (isCommercial(a)) "a commercial break" else a.type.lowercase()))
        }
    }
    val prompt = active?.takeIf { mode == Prefs.ASK && it.startMs !in handled }

    if (controlsOpen) BackHandler { controlsOpen = false }
    if (infoOpen) BackHandler { infoOpen = false }

    // One for the screen's life: rebuilt on every tick, a button lost its click between key down and up.
    val exit by rememberUpdatedState(onExit)
    val playNext by rememberUpdatedState(onPlayRecording)
    val toLive by rememberUpdatedState(onBackToLive)
    val actions =
        remember {
            object : RecordingActions {
                override fun playPause() {
                    if (player.isPlaying) player.pause() else player.play()
                    osdUntil = System.currentTimeMillis() + 5000
                }

                override fun seek(forward: Boolean) {
                    if (forward) player.seekForward() else player.seekBack()
                    osdUntil = System.currentTimeMillis() + 4000
                }

                // Stop: from a live TV buffer, done with the channel too, so its tuner input is freed now.
                override fun stop() {
                    live?.let { Jdvr.backend.stopBuffering(it) }
                    exit()
                }

                override fun previous() {
                    rec.previous?.let(playNext)
                }

                // Next: the next recording, or from a buffer's last programme, live.
                override fun next() {
                    val n = rec.next
                    when {
                        n != null -> playNext(n)
                        live != null -> toLive(live)
                    }
                }

                // ● on a live TV buffer keeps it as a recording (or lets it go again).
                override fun toggleKeep() {
                    val b = rec.buffer ?: return
                    scope.launch {
                        Jdvr.backend.keepBuffer(b.id, !b.kept)?.let { rec.buffer = it }
                        flash(if (b.kept) "Won't keep this buffer" else "Recording kept")
                    }
                }

                override fun showOsd() {
                    osdUntil = System.currentTimeMillis() + 5000
                }
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
                e.type == KeyEventType.KeyDown && rec.error == null && actions.onMediaKey(e.key.nativeKeyCode, player.isPlaying)
            }.onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || rec.error != null || controlsOpen || infoOpen) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        actions.seek(false)
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        actions.seek(true)
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
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> {
                        controlsOpen = true
                    }

                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                        if (live != null) onBackToLive(live) else actions.showOsd()
                    }

                    AndroidKeyEvent.KEYCODE_INFO -> {
                        infoOpen = rec.details != null
                        if (!infoOpen) actions.showOsd()
                    }

                    else -> {
                        return@onKeyEvent false
                    }
                }
                true
            },
    ) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                this.player = player
            }
        }, modifier = Modifier.fillMaxSize())

        val osd = !infoOpen && (System.currentTimeMillis() < osdUntil || !player.isPlaying || controlsOpen)
        // The top box takes the top of the screen: the skip prompt and notes go just below it.
        val cornerTop = if (osd && prefs.infoBoxTop && rec.details != null) 176.dp else 32.dp
        prompt?.let {
            Text(
                skipLabel(it) + "  ›  OK",
                color = Jc.OnFocus,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 32.dp, end = 32.dp, top = cornerTop)
                        .background(Jc.Focus, RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        note?.let {
            Text(
                it,
                color = Jc.Text,
                fontSize = 16.sp,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 32.dp, end = 32.dp, top = cornerTop)
                        .background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                        .padding(14.dp),
            )
        }

        if (osd && prefs.infoBoxTop) rec.details?.let { RecordingTopBox(it, rec.nextTitle, Modifier.align(Alignment.TopCenter)) }
        if (infoOpen) {
            rec.details?.let {
                RecordingInfoPanel(
                    d = it,
                    commercials = rec.commercialsLabel,
                    note = infoNote,
                    first = infoFocus,
                    onClose = { infoOpen = false },
                    onNote = { n -> infoNote = n },
                    onDeleted = onExit,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        if (osd) {
            RecordingBar(
                title = rec.playback?.name ?: "",
                position = position,
                duration = player.duration.takeIf { it > 0 } ?: rec.playback?.durationMs ?: 0L,
                segments = rec.segments,
                inProgress = rec.inProgress,
                buffer = rec.buffer,
                formats = formats,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (!controlsOpen) {
                    Text(
                        (if (player.isPlaying) "" else "Paused · ") + "← 10 s · → 30 s · OK controls" + (if (live != null) " · ▲ back to live" else "") + " · Back leave",
                        color = Jc.Muted,
                        fontSize = 13.sp,
                    )
                    return@RecordingBar
                }
                TransportBar(
                    previous = TransportAction(TransportIcon.Previous, rec.previous != null) { actions.previous() },
                    rewind = TransportAction(TransportIcon.Rewind) { actions.seek(false) },
                    stop = TransportAction(TransportIcon.Stop) { actions.stop() },
                    playPause = TransportAction(if (player.isPlaying) TransportIcon.Pause else TransportIcon.Play) { actions.playPause() },
                    forward = TransportAction(TransportIcon.Forward) { actions.seek(true) },
                    next = TransportAction(TransportIcon.Next, rec.next != null || live != null) { actions.next() },
                    playFocus = playFocus,
                    record = TransportAction(TransportIcon.Record, enabled = rec.buffer != null, active = rec.buffer?.kept == true) { actions.toggleKeep() },
                ) {
                    if (rec.details != null) {
                        Button("Info") {
                            controlsOpen = false
                            infoOpen = true
                        }
                    }
                    TrackButtons(player)
                    if (live != null) Button("Back to live") { onBackToLive(live) }
                }
                // Editing (paused, users allowed to edit recordings), on its own line.
                if (edit != null && !player.isPlaying && live == null && !rec.inProgress && com.mackandall.jdvr.ui
                        .cuttingFor(itemId.toString()) == null
                ) {
                    EditRow(editor, edit)
                }
            }
        }
        rec.error?.let {
            Column(Modifier.align(Alignment.Center).background(Jc.Sheet, RoundedCornerShape(14.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(it, color = Jc.Amber, fontSize = 18.sp)
                Button("Back", primary = true, onClick = onExit)
            }
        }
    }
}

/** What the recording player's controls and the remote's keys do. */
private interface RecordingActions {
    fun playPause()

    fun seek(forward: Boolean)

    fun stop()

    fun previous()

    fun next()

    fun toggleKeep()

    fun showOsd()
}

/** The remote's media keys: they work whether or not the controls are showing. */
private fun RecordingActions.onMediaKey(
    code: Int,
    playing: Boolean,
): Boolean {
    when (code) {
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playPause()
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (!playing) playPause()
        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (playing) playPause()
        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seek(false)
        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(true)
        AndroidKeyEvent.KEYCODE_MEDIA_STOP -> stop()
        AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
        AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> next()
        AndroidKeyEvent.KEYCODE_MEDIA_RECORD -> toggleKeep()
        else -> return false
    }
    return true
}

@OptIn(UnstableApi::class)
private fun recordingPlayer(
    context: android.content.Context,
    paused: Boolean,
): ExoPlayer {
    val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(8 * 1024 * 1024)
    return ExoPlayer
        .Builder(context, renderersFactory(context))
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Jdvr.http), extractors))
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()
        .apply {
            playWhenReady = !paused
            applySubtitlePreference(this)
        }
}

/** The bottom bar: title, the progress bar with its marks, then [below] (hints, or the controls). */
@Composable
private fun RecordingBar(
    title: String,
    position: Long,
    duration: Long,
    segments: List<Segment>,
    inProgress: Boolean,
    buffer: Buffer?,
    formats: PlayingFormats?,
    modifier: Modifier,
    below: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(Jc.Ink.copy(alpha = 0.94f)).padding(horizontal = 36.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TitleWithFormats(formats, Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (inProgress) Text("● still recording", color = Jc.Recording, fontSize = 15.sp, maxLines = 1, softWrap = false)
                buffer?.let { Text(if (it.kept) "Live TV buffer · kept" else "Live TV buffer", color = Jc.Muted, fontSize = 15.sp, maxLines = 1, softWrap = false) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(clock(position), color = Jc.Muted, fontSize = 14.sp)
            SegmentBar(position, duration, segments, Modifier.weight(1f))
            Text(clock(duration), color = Jc.Muted, fontSize = 14.sp)
        }
        below()
    }
}

/** The progress bar with commercials (amber) and intros / recaps / previews (purple) marked. */
@Composable
private fun SegmentBar(
    position: Long,
    duration: Long,
    segments: List<Segment>,
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
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(f)
                    .padding(vertical = 3.dp)
                    .background(Jc.Focus.copy(alpha = 0.85f), RoundedCornerShape(3.dp)),
            )
        }
    }
}
