package com.mackandall.jdvr.player

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.ItemDetails
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.MarkSymbols
import com.mackandall.jdvr.data.TunerStatus
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.MarkChips
import com.mackandall.jdvr.ui.Marks
import com.mackandall.jdvr.ui.ProgramActionsHost
import com.mackandall.jdvr.ui.day
import com.mackandall.jdvr.ui.rememberMarkSymbols
import com.mackandall.jdvr.ui.seeThrough
import com.mackandall.jdvr.ui.time
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import android.view.KeyEvent as AndroidKeyEvent


/**
 * Full-screen live TV. Up/down (or channel up/down) change channel, digits type a channel number,
 * OK shows the info bar, Back leaves. Before a channel starts, JellyDVR Schedule is asked whether a
 * tuner is free: if not, "All tuners are busy" lists what they're doing, with Watch buttons.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(
    picture: MainPicture,
    onExit: () -> Unit,
    onChannelChange: (UUID) -> Unit,
    onPlayRecording: (com.mackandall.jdvr.PlayRecording) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val symbols = rememberMarkSymbols()
    val focus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    // The stream lives in the picture (it goes on playing in the guide after Back); this only shows it.
    val ui = picture.ui
    val info = remember { LiveInfo() }
    val player = picture.player
    val stream = picture.stream
    val extra = LivePictures.extra
    val swapped = LivePictures.swapped
    // The big picture: what the info bars show and the remote's pause, rewind and channel keys act
    // on (after Swap, the second picture's channel).
    val bigExtra = extra?.takeIf { swapped }
    val big: LiveTransport = bigExtra?.transport ?: stream
    val bigPlayer = bigExtra?.player ?: player
    val channelId = big.channelId
    val formats = rememberPlayingFormats(bigPlayer)
    var tick by remember { mutableIntStateOf(0) }
    val exit by androidx.compose.runtime.rememberUpdatedState(onExit)
    // Another channel for the big picture.
    val change: (UUID) -> Unit = { id ->
        if (bigExtra != null) info.channel(id)?.let { LivePictures.changeExtra(context, it) } else onChannelChange(id)
    }
    // Picture in picture: the channel list to pick its channel from, and its own menu.
    var pickPicture by remember { mutableStateOf(false) }
    var pictureMenu by remember { mutableStateOf(false) }
    // Hold OK swaps the pictures: OK down waits to see whether it's held; let go first, it's a press.
    var okDownAt by remember { mutableStateOf(0L) }
    var okHeld by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        picture.setVolume(if (swapped) 0f else 1f)
        info.loadChannels()
        focus.requestFocus()
    }

    // Leaving the app (Home, another app, the TV sleeping) leaves live TV too: it frees the tuner.
    OnLeaveApp { exit() }
    // The picture in picture's panels and questions have the remote while they show.
    val pictureAsks = LivePictures.giveWay != null || extra?.state == PictureState.RECORDING_STOPPED
    LaunchedEffect(pickPicture, pictureMenu, pictureAsks) {
        ui.pictureOpen = pickPicture || pictureMenu || pictureAsks
        // After the panel's focused row has gone: asked at once, the request was lost and the
        // remote did nothing until focus came back.
        if (!ui.pictureOpen) {
            delay(50)
            focus.tryFocus()
        }
    }
    LaunchedEffect(stream.tuning, stream.attempt) {
        if (!stream.tuning && ui.note?.startsWith("Reconnecting") == true) ui.note = null
    }

    LaunchedEffect(channelId, ui.actionsOpen) { info.loadRecording(channelId) }
    LaunchedEffect(ui.actionsOpen) {
        if (ui.actionsOpen) {
            ui.osdUntil = Long.MAX_VALUE
            delay(50)
            playFocus.tryFocus()
        } else {
            ui.showOsd(3000)
            focus.tryFocus()
        }
    }
    // The controls tuck away after 8 s untouched.
    LaunchedEffect(ui.actionsOpen, ui.lastKeyAt, big.pausedAt) {
        if (!ui.actionsOpen || big.pausedAt != null) return@LaunchedEffect
        delay(8000)
        ui.actionsOpen = false
    }
    if (ui.actionsOpen) androidx.activity.compose.BackHandler { ui.actionsOpen = false }
    if (ui.miniOpen) androidx.activity.compose.BackHandler { ui.miniOpen = false }
    if (ui.infoOpen) androidx.activity.compose.BackHandler { ui.infoOpen = false }
    LaunchedEffect(ui.infoOpen) { if (!ui.infoOpen) focus.tryFocus() }
    LaunchedEffect(ui.miniOpen) { if (!ui.miniOpen) focus.tryFocus() }
    LaunchedEffect(channelId, info.channel(channelId)?.now?.id) { info.loadNow(channelId) }

    // A typed channel number goes after 2 s without another digit.
    LaunchedEffect(ui.typedAt) {
        if (ui.typed.isEmpty()) return@LaunchedEffect
        delay(2000)
        info.channels.firstOrNull { it.number == ui.typed }?.let { change(it.id) }
        ui.typed = ""
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // The controls' handlers stay the same between key down and key up: rebuilt (every key press
    // recomposes the screen), the row's buttons lost their clicks.
    val playRecording by androidx.compose.runtime.rememberUpdatedState(onPlayRecording)
    val record: () -> Unit =
        remember(channelId) {
            {
                scope.launch {
                    val said = info.record(channelId) ?: return@launch
                    ui.flash(said, 4000)
                    info.loadBuffers(channelId)
                    info.loadChannels()
                }
            }
        }

    // Stop: done with this channel, so its tuner input is freed now rather than after the grace.
    val stop: () -> Unit =
        remember(channelId) {
            {
                Jdvr.backend.stopBuffering(stream.channelId)
                exit()
            }
        }
    val fromStart: (UUID) -> Unit = remember(channelId) { { rec -> playRecording(com.mackandall.jdvr.PlayRecording(rec, startMs = 0L, liveChannelId = channelId)) } }

    fun step(delta: Int) {
        val channels = info.channels
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.id == channelId }
        change(channels[((if (i < 0) 0 else i) + delta).mod(channels.size)].id)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                ui.lastKeyAt = System.currentTimeMillis()
                val code = e.key.nativeKeyCode
                val isOk = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER
                // Only on the picture itself: no menu, list or question has the remote, no number typed.
                val canHold =
                    Jdvr.prefs.holdOkSwap && extra != null && stream.showing && !ui.anyPanel && !pictureMenu && !pickPicture &&
                        ui.typed.isEmpty() && extra.state != PictureState.RECORDING_STOPPED && LivePictures.giveWay == null
                if (isOk && (canHold || okDownAt != 0L)) {
                    val now = System.currentTimeMillis()
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                            okDownAt = now
                            okHeld = false
                        }

                        e.type == KeyEventType.KeyDown -> {
                            // A repeat means held: Android repeats a key only once it's been held for the
                            // long-press time (400 ms on the Shield and onn); a tap never repeats.
                            if (!okHeld && okDownAt != 0L) {
                                okHeld = true
                                LivePictures.swap()
                                ui.flash(if (LivePictures.swapped) "Swapped · hold OK to swap back" else "Swapped back", 2500)
                            }
                        }

                        else -> {
                            // Let go before it counted as held: the usual OK.
                            val held = okHeld
                            okDownAt = 0L
                            okHeld = false
                            if (!held && big.showing) {
                                onRemoteKey(code, big, ui, step = ::step, onNumber = { n -> info.channels.firstOrNull { it.number == n }?.let { change(it.id) } })
                            }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown || !big.showing) return@onPreviewKeyEvent false
                // The mini guide uses ⏩ ⏪ to jump two hours.
                if (ui.miniOpen && e.key.nativeKeyCode in setOf(AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_REWIND)) return@onPreviewKeyEvent false
                big.onMediaKey(e.key.nativeKeyCode, record = record, stop = stop)
            }.onKeyEvent { e ->
                e.type == KeyEventType.KeyDown && big.showing &&
                    onRemoteKey(e.key.nativeKeyCode, big, ui, step = ::step, onNumber = { n -> info.channels.firstOrNull { it.number == n }?.let { change(it.id) } })
            },
    ) {
        // Swapped (picture in picture): the second picture shows big, this channel in the corner.
        LivePictureView(bigPlayer, Modifier.fillMaxSize())

        val channel = info.channel(channelId)
        // Read every second: the info bars come and go with the time.
        val osd = tick.let { big.tuning || System.currentTimeMillis() < ui.osdUntil || ui.actionsOpen || big.pausedAt != null }
        if (big.tuning && big.showing && bigExtra == null) TuningFiller(channel)
        if (!ui.miniOpen && !ui.infoOpen && osd && big.showing) {
            channel?.let { TopInfoBox(it, symbols, info.nowDetails, info.nextUp, Modifier.align(Alignment.TopCenter)) }
        }
        if (!ui.miniOpen && (osd || stream.error != null)) {
            LiveControls(
                channel,
                symbols,
                big,
                info,
                ui,
                formats,
                bigPlayer,
                playFocus,
                Modifier.align(Alignment.BottomCenter),
                tick = tick,
                onRecord = record,
                onStop = stop,
                onFromStart = fromStart,
                onPicture = {
                    ui.actionsOpen = false
                    when {
                        !LivePictures.canPip -> ui.flash("This TV can play one picture at a time.", 4000)
                        LivePictures.extra == null -> pickPicture = true
                        else -> pictureMenu = true
                    }
                },
            )
        }
        LiveMessages(stream, ui, onExit)
        // Picture in picture: the small picture, its menu and the channel list to pick it from.
        if (extra != null) {
            PictureInPicture(picture, extra, swapped, info.channel(stream.channelId), barUp = !ui.miniOpen && !ui.infoOpen && osd && big.showing, onFailed = { msg ->
                LivePictures.closeExtra()
                ui.flash(msg, 5000)
            })
            if (pictureMenu) {
                PictureMenu(extra, swapped, onChange = {
                    pictureMenu = false
                    pickPicture = true
                }, onClose = { pictureMenu = false })
            }
        }
        if (pickPicture && info.channels.isNotEmpty()) {
            PicturePicker(info.channels, stream.channelId, onPick = { c ->
                pickPicture = false
                LivePictures.openExtra(context, c)
                if (Jdvr.prefs.holdOkSwap) ui.flash("Hold OK to swap the pictures", 3000)
            }, onClose = { pickPicture = false })
        }
        // Info: the guide's programme panel on the right, with all its options.
        if (ui.infoOpen) {
            channel?.now?.let { now ->
                ProgramActionsHost(onDismiss = { ui.infoOpen = false }) {
                    com.mackandall.jdvr.ui.ProgramPanel(
                        program = now,
                        channel = channel,
                        symbols = symbols,
                        onWatch = { ui.infoOpen = false },
                        onWatchFromStart = { id ->
                            ui.infoOpen = false
                            onPlayRecording(com.mackandall.jdvr.PlayRecording(id, startMs = 0L, liveChannelId = channelId))
                        },
                        onPlayItem = {
                            ui.infoOpen = false
                            onPlayRecording(it)
                        },
                        onChanged = {},
                        onClose = { ui.infoOpen = false },
                        onActed = { msg ->
                            ui.infoOpen = false
                            msg?.let { ui.flash(it, 4000) }
                            scope.launch {
                                delay(1500)
                                info.loadChannels()
                                info.loadBuffers(channelId)
                            }
                        },
                        modifier = Modifier.width(400.dp),
                    )
                }
            } ?: run { ui.infoOpen = false }
        }
        if (ui.miniOpen && info.channels.isNotEmpty()) {
            MiniGuide(
                channels = info.channels,
                currentChannelId = channelId,
                symbols = symbols,
                onWatch = {
                    ui.miniOpen = false
                    if (it != channelId) change(it)
                },
                onClose = { ui.miniOpen = false },
                modifier = Modifier.fillMaxSize(),
                buffers = info.bufferIds,
                onPlayRecording = {
                    ui.miniOpen = false
                    onPlayRecording(it)
                },
            )
        }
        stream.busy?.let { status ->
            TunersBusy(status, channel, onWatch = { onChannelChange(it) }, onCancel = onExit, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** [action] when the app goes out of sight (Home, another app, the TV going to sleep). */
@Composable
internal fun OnLeaveApp(action: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val current by androidx.compose.runtime.rememberUpdatedState(action)
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) current() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** The remote's media keys: they work whether or not the controls are showing. */
private fun LiveTransport.onMediaKey(
    code: Int,
    record: () -> Unit,
    stop: () -> Unit,
): Boolean {
    when (code) {
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playPause()
        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> resume()
        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> rewind()
        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> forward()
        AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> toLive()
        AndroidKeyEvent.KEYCODE_MEDIA_RECORD -> record()
        AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> backToTuneIn()
        AndroidKeyEvent.KEYCODE_MEDIA_STOP -> stop()
        else -> return false
    }
    return true
}

/** The remote over the bare picture: channels, the mini guide, the controls, rewind and digits. */
private fun onRemoteKey(
    code: Int,
    stream: LiveTransport,
    ui: LiveOverlays,
    step: (Int) -> Unit,
    onNumber: (String) -> Unit,
): Boolean {
    // ▼ from the controls row (nothing below it) or Guide: the mini guide, as the hints say.
    if (ui.actionsOpen && !ui.miniOpen && !ui.infoOpen && code in setOf(AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_GUIDE)) {
        ui.actionsOpen = false
        ui.miniOpen = true
        return true
    }
    // A panel has the remote.
    if (ui.anyPanel) return false
    when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
            step(1)
        }

        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
            step(-1)
        }

        AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_GUIDE -> {
            ui.miniOpen = true
        }

        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
            if (ui.typed.isNotEmpty()) {
                onNumber(ui.typed)
                ui.typed = ""
            } else if (stream.pausedAt != null) {
                stream.resume()
            } else {
                ui.actionsOpen = true
            }
        }

        AndroidKeyEvent.KEYCODE_INFO -> {
            ui.infoOpen = true
        }

        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
            stream.rewind()
        }

        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
            stream.forward()
        }

        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
            ui.typed = (ui.typed + (code - AndroidKeyEvent.KEYCODE_0)).takeLast(4)
            ui.typedAt = System.currentTimeMillis()
        }

        else -> {
            return false
        }
    }
    return true
}

/** The bottom info bar, and with OK the transport row and buttons in it. */
@Composable
private fun LiveControls(
    channel: Channel?,
    symbols: MarkSymbols?,
    stream: LiveTransport,
    info: LiveInfo,
    ui: LiveOverlays,
    formats: PlayingFormats?,
    player: ExoPlayer,
    playFocus: FocusRequester,
    modifier: Modifier,
    // The screen's 1-second tick: how far behind live (and the clock) count on while it shows.
    tick: Int,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFromStart: (UUID) -> Unit,
    onPicture: () -> Unit,
) {
    val offset = tick.let { stream.behindLiveMs() }
    val paused = stream.pausedAt
    // Paused: behind from the first second, never "● LIVE" beside the Paused line.
    val behind =
        if (offset > 15_000) {
            offset
        } else if (paused != null) {
            offset.coerceAtLeast(1_000)
        } else {
            0L
        }
    val recording = info.realRecording(stream.channelId)
    InfoBar(channel, symbols, modifier, behindMs = behind, recording = recording, formats = formats, watchingAt = LocalDateTime.now().minusNanos(offset * 1_000_000), bufferFrom = stream.bufferFrom(offset), pausedAt = paused) {
        if (!ui.actionsOpen) return@InfoBar
        val rewound = stream.onHls
        TransportBar(
            previous = TransportAction(TransportIcon.Previous) { stream.backToTuneIn() },
            rewind = TransportAction(TransportIcon.Rewind) { stream.rewind() },
            stop = TransportAction(TransportIcon.Stop) { onStop() },
            playPause = TransportAction(if (paused == null) TransportIcon.Pause else TransportIcon.Play) { stream.playPause() },
            forward = TransportAction(TransportIcon.Forward, rewound) { stream.forward() },
            // Next = back to live.
            next = TransportAction(TransportIcon.Next, rewound) { stream.toLive() },
            playFocus = playFocus,
            clock = false,
            record = channel?.now?.takeIf { com.mackandall.jdvr.ui.TvRights.info.record }?.let { TransportAction(TransportIcon.Record, active = recording) { onRecord() } },
            picture = TransportAction(TransportIcon.Picture) { onPicture() },
        ) {
            Button("Info") {
                ui.actionsOpen = false
                ui.infoOpen = true
            }
            TrackButtons(player)
            info.fromStart?.let { rec -> Button("From start") { onFromStart(rec) } }
        }
    }
}

/** Messages over the picture: paused (behind the mini guide), notes, typed digits, and errors. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LiveMessages(
    stream: LiveStream,
    ui: LiveOverlays,
    onExit: () -> Unit,
) {
    // In the bar when it shows; up here only behind the mini guide.
    stream.pausedAt?.takeIf { ui.miniOpen }?.let { at ->
        Text(
            "❚❚  Paused at " + LocalDateTime.ofInstant(at, ZoneId.systemDefault()).time() + "  ·  ▶ or OK to resume",
            color = Jc.Text,
            fontSize = 18.sp,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(28.dp)
                    .background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    ui.note?.let {
        Text(
            it,
            color = Jc.Text,
            fontSize = 16.sp,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(28.dp)
                    .background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                    .padding(14.dp),
        )
    }
    if (ui.typed.isNotEmpty()) {
        Text(
            ui.typed,
            color = Jc.Text,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }
    stream.error?.let {
        Column(
            Modifier.align(Alignment.Center).background(Jc.Sheet, RoundedCornerShape(14.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(it, color = Jc.Amber, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Try again", primary = true) { stream.retry() }
                Button("Back", onClick = onExit)
            }
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
        val art = now?.let { Jdvr.backend.imageUrl(it.id, 720) }
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
                coil3.compose.AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 140.dp)
                            .width(260.dp)
                            .height(160.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x73080B10)))
        Row(
            // Middle right: the top box and the info bar are up while it tunes.
            Modifier
                .align(Alignment.CenterEnd)
                .padding(28.dp)
                .background(Jc.Ink.copy(alpha = 0.8f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
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
    val t =
        androidx.compose.animation.core
            .rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f,
        360f,
        androidx.compose.animation.core
            .infiniteRepeatable(
                androidx.compose.animation.core
                    .tween(900, easing = androidx.compose.animation.core.LinearEasing),
            ),
        label = "angle",
    )
    androidx.compose.foundation.Canvas(Modifier.width(18.dp).height(18.dp)) {
        val stroke =
            androidx.compose.ui.graphics.drawscope
                .Stroke(width = 3.dp.toPx())
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
    symbols: MarkSymbols?,
    details: ItemDetails?,
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
                listOfNotNull(
                    details
                        ?.genres
                        ?.take(3)
                        ?.joinToString(" · ")
                        ?.ifEmpty { null },
                    details?.item?.year?.toString(),
                ).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
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
    symbols: MarkSymbols?,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▲ / CH change channel · ▼ mini guide · 0–9 channel · ← 10 s back · → 30 s ahead · OK controls · Back leave", color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The time: here, as the transport row is full on live TV (audio, From start).
            Text(LocalDateTime.now().format(NowFormat), color = Jc.Text, fontSize = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 16.dp))
        }
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
    val total =
        Duration
            .between(start, end)
            .seconds
            .coerceAtLeast(1)
            .toFloat()

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
            val r =
                androidx.compose.ui.geometry
                    .CornerRadius(h / 2)

            fun seg(
                a: Float,
                b: Float,
                c: Color,
            ) {
                if (b > a) {
                    drawRoundRect(
                        c,
                        androidx.compose.ui.geometry
                            .Offset(a * size.width, y),
                        androidx.compose.ui.geometry
                            .Size((b - a) * size.width, h),
                        r,
                    )
                }
            }
            seg(0f, 1f, line)
            seg(0f, from, aired)
            seg(from, watching, focus)
            if (!behind) return@Canvas
            // The buffer ahead of you: stripes.
            val a = watching * size.width
            val b = live * size.width
            if (b > a) {
                drawRoundRect(
                    focus.copy(alpha = 0.12f),
                    androidx.compose.ui.geometry
                        .Offset(a, y),
                    androidx.compose.ui.geometry
                        .Size(b - a, h),
                    r,
                )
                val clip =
                    androidx.compose.ui.graphics
                        .Path()
                        .apply {
                            addRoundRect(
                                androidx.compose.ui.geometry
                                    .RoundRect(a, y, b, y + h, r),
                            )
                        }
                clipPath(clip) {
                    val step = 7.dp.toPx()
                    var x = a - h
                    while (x < b) {
                        drawLine(
                            focus.copy(alpha = 0.75f),
                            androidx.compose.ui.geometry
                                .Offset(x, y + h),
                            androidx.compose.ui.geometry
                                .Offset(x + h, y),
                            strokeWidth = 3.dp.toPx(),
                        )
                        x += step
                    }
                }
            }
            // Live: a red tick.
            drawRect(
                red,
                androidx.compose.ui.geometry
                    .Offset(b - 1.dp.toPx(), barMid.toPx() - 9.dp.toPx()),
                androidx.compose.ui.geometry
                    .Size(2.dp.toPx(), 18.dp.toPx()),
            )
            // The bracket from you to live, under the bar.
            val by = barMid.toPx() + 12.dp.toPx()
            val bh = 6.dp.toPx()
            val sw = 1.dp.toPx()
            drawLine(
                amber,
                androidx.compose.ui.geometry
                    .Offset(a, by),
                androidx.compose.ui.geometry
                    .Offset(a, by + bh),
                sw,
            )
            drawLine(
                amber,
                androidx.compose.ui.geometry
                    .Offset(a, by + bh),
                androidx.compose.ui.geometry
                    .Offset(b, by + bh),
                sw,
            )
            drawLine(
                amber,
                androidx.compose.ui.geometry
                    .Offset(b, by),
                androidx.compose.ui.geometry
                    .Offset(b, by + bh),
                sw,
            )
            // You: a thumb.
            val tw = 8.dp.toPx()
            val th = 22.dp.toPx()
            val tl =
                androidx.compose.ui.geometry
                    .Offset(a - tw / 2, barMid.toPx() - th / 2)
            drawRoundRect(
                Color.Black.copy(alpha = 0.5f),
                tl +
                    androidx.compose.ui.geometry
                        .Offset(0f, 2.dp.toPx()),
                androidx.compose.ui.geometry
                    .Size(tw, th),
                androidx.compose.ui.geometry
                    .CornerRadius(tw / 2),
            )
            drawRoundRect(
                Color.White,
                tl,
                androidx.compose.ui.geometry
                    .Size(tw, th),
                androidx.compose.ui.geometry
                    .CornerRadius(tw / 2),
            )
            drawRoundRect(
                focus,
                tl,
                androidx.compose.ui.geometry
                    .Size(tw, th),
                androidx.compose.ui.geometry
                    .CornerRadius(tw / 2),
                style =
                    androidx.compose.ui.graphics.drawscope
                        .Stroke(2.dp.toPx()),
            )
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
    status: TunerStatus,
    channel: Channel?,
    onWatch: (UUID) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryFocus() }
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
internal object NoRetryOnServerError : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = info.exception
        if (e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && e.responseCode >= 500) return androidx.media3.common.C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}

/** What went wrong with a live channel, in words. */
internal fun liveErrorText(e: PlaybackException): String =
    when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
            "The server couldn't start this channel. It may be off the air, or its tuner may be busy or not working."
        }

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
            "Lost the connection to the server."
        }

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> {
            "This TV couldn't play this channel's picture or sound."
        }

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
            "The channel's stream came through damaged."
        }

        else -> {
            "Playback stopped (${e.errorCodeName})."
        }
    }
