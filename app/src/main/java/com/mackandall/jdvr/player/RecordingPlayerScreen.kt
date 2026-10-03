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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
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
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.UUID

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A recording, full screen. Resumes where it was left; ← / → skip back 10 s / ahead 30 s, OK pauses
 * and shows the bar, Back leaves. Reports the position, so watched and resume work everywhere.
 */
@OptIn(UnstableApi::class)
@Composable
fun RecordingPlayerScreen(
    itemId: UUID,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var playback by remember { mutableStateOf<LiveTvRepository.ItemPlayback?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableStateOf(System.currentTimeMillis() + 5000) }
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    val player =
        remember {
            val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(8 * 1024 * 1024)
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Jdvr.http), extractors))
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
                .apply { playWhenReady = true }
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
                    player.setMediaItem(MediaItem.fromUri(p.url), p.resumeMs)
                    player.prepare()
                    Jdvr.liveTv.reportItem(p, p.resumeMs, paused = false, event = "start")
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
            current?.let { p -> runBlocking { runCatching { Jdvr.liveTv.reportItem(p, position, paused = true, event = "stop") } } }
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (true) {
            delay(500)
            tick++
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || error != null) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        player.seekBack()
                        osdUntil = System.currentTimeMillis() + 4000
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        player.seekForward()
                        osdUntil = System.currentTimeMillis() + 4000
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (player.isPlaying) player.pause() else player.play()
                        osdUntil = System.currentTimeMillis() + 5000
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_INFO -> {
                        osdUntil = System.currentTimeMillis() + 5000
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } }, modifier = Modifier.fillMaxSize())

        tick.let {
            if (System.currentTimeMillis() < osdUntil || !player.isPlaying) {
                val position = player.currentPosition
                val duration = player.duration.takeIf { it > 0 } ?: playback?.durationMs ?: 0L
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xF00D1117)).padding(horizontal = 36.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(playback?.name ?: "", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(clock(position), color = Jc.Muted, fontSize = 14.sp)
                        Box(Modifier.weight(1f).height(6.dp).background(Jc.Line, RoundedCornerShape(3.dp))) {
                            val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Jc.Focus, RoundedCornerShape(3.dp)))
                        }
                        Text(clock(duration), color = Jc.Muted, fontSize = 14.sp)
                    }
                    Text(
                        (if (player.isPlaying) "" else "Paused · ") + "← 10 s back · → 30 s ahead · OK play/pause · Back leave",
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
