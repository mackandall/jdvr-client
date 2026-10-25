package com.mackandall.jdvr.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LivePlayback
import com.mackandall.jdvr.data.TunerStatus
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

private const val MAX_RECONNECTS = 3
private val RECONNECT_DELAYS_MS = longArrayOf(2000, 4000, 8000)
private const val STALL_MS = 12_000L
private const val GOOD_SPELL_MS = 90_000L

/**
 * One channel's live stream in the player: the tuner check, opening it, reconnecting by itself, and
 * pause and rewind. A new one for each channel; [close] when the player moves on.
 *
 * Hybrid: the channel starts as direct play (quick to tune) while the server starts an HLS copy of
 * the same stream; pause and rewind move over to the HLS copy, where they're instant.
 */
@Stable
internal class LiveStream(
    val channelId: UUID,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val ui: LiveOverlays,
) {
    // Between choosing a channel and its first picture: the tuning filler covers the old one.
    var tuning by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    // All tuners taken: what they're doing, instead of the picture.
    var busy by mutableStateOf<TunerStatus?>(null)
        private set
    var playback by mutableStateOf<LivePlayback?>(null)
        private set

    // Try again: a fresh start of the same channel (a new stream from the server).
    var attempt by mutableIntStateOf(0)
        private set

    // Reconnecting by itself: a free channel's stream can break or freeze mid-programme (an ad
    // spliced in with a wild timestamp froze the server's transcode, 2026-10-15). Up to three fresh
    // starts in a row, a little further apart each time; the error only shows if they all fail.
    private var reconnects by mutableIntStateOf(0)
    var pausedAt by mutableStateOf<Instant?>(null)
        private set
    var onHls by mutableStateOf(false)
        private set
    private var directStartedAt by mutableLongStateOf(0L)
    private var hlsStartedAt by mutableStateOf<Long?>(null)

    // Where pause and rewind go: Jellyfin's HLS copy, or NextPVR's buffer file (found once the stream plays).
    private var copyUrl by mutableStateOf<String?>(null)
    private var swapJob: Job? = null

    // Where the buffer file last ended (it's reopened there; ending at the same spot again: it stopped growing).
    private var lastEnd = -1L

    // How far the buffer file has played (sampled every 2 s): the player can say 0 at its end.
    private var copyPlayedTo = 0L

    // What this start opened (each Try again or reconnect is a new start), to close when it ends.
    private var opened: LivePlayback? = null

    /** Nothing in the way of the picture (no tuners-busy list, no error). */
    val showing get() = busy == null && error == null

    val canHls get() = copyUrl != null && hlsStartedAt != null

    // NextPVR: its buffer file for this stream, once the tuner is writing it (both begin at tune-in).
    private suspend fun findCopy(
        p: LivePlayback,
        notThis: String? = null,
    ) {
        val url = Jdvr.backend.liveCopyUrl(p, notThis) ?: return
        if (playback?.channelId != p.channelId) return
        copyUrl = url
        hlsStartedAt = directStartedAt
    }

    /** A fresh start of the channel, by itself; false once the tries are used up (the error then shows). */
    fun reconnect(why: String): Boolean {
        if (reconnects >= MAX_RECONNECTS) return false
        reconnects++
        Timber.i("Reconnecting channel %s (%d of %d): %s", channelId, reconnects, MAX_RECONNECTS, why)
        val tryNo = reconnects
        player.stop()
        error = null
        tuning = true
        ui.note = "Reconnecting… ($tryNo of $MAX_RECONNECTS)"
        scope.launch {
            delay(RECONNECT_DELAYS_MS[tryNo - 1])
            attempt++
        }
        return true
    }

    /** The error's Try again: a fresh start, with the reconnect tries back to three. */
    fun retry() {
        error = null
        reconnects = 0
        attempt++
    }

    /** Start the channel: tuner check, open the stream, play, tell Jellyfin, and report progress. */
    fun start(): Job =
        MainScope().launch {
            error = null
            busy = null
            tuning = true
            ui.showOsd(6000)
            try {
                val tuners = Jdvr.backend.tuners(channelId)
                if (tuners != null && tuners.isChannel && tuners.full) {
                    tuning = false
                    busy = tuners
                    return@launch
                }
                // Opening takes 10+ s, and Back, Stop or a channel change meanwhile cancels this, but
                // the server opens the stream anyway: wait for it regardless and close what comes back,
                // or that tuner input stays taken until Jellyfin restarts (four were, 2026-10-06).
                val p = withContext(NonCancellable) { Jdvr.backend.startLive(channelId) }
                if (!isActive) {
                    Timber.i("Channel %s opened after its player left; closing it", channelId)
                    p.liveStreamId?.let { id -> MainScope().launch { attempt { Jdvr.backend.closeLiveStream(id) } } }
                    return@launch
                }
                playback = p
                opened = p
                Jdvr.session.lastChannelId = channelId.toString()
                Jdvr.session.pushRecentChannel(channelId.toString())
                player.setMediaItem(MediaItem.fromUri(p.url))
                player.playWhenReady = true // a new channel plays even if the last was paused
                player.prepare()
                directStartedAt = android.os.SystemClock.elapsedRealtime()
                onHls = p.hlsUrl == p.url
                copyUrl = p.hlsUrl
                hlsStartedAt = null
                p.hlsUrl?.takeIf { !onHls }?.let { hls ->
                    launch {
                        hlsStartedAt = android.os.SystemClock.elapsedRealtime()
                        Jdvr.backend.warmHls(hls)
                    }
                }
                if (p.hlsUrl == null && Jdvr.backend.liveCopyIsFile) launch { findCopy(p) }
                Jdvr.backend.reportStart(p)
                while (true) {
                    delay(10_000)
                    Jdvr.backend.reportProgress(p, paused = !player.isPlaying)
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

    /** Stops the picture and closes the live stream [start] opened (that frees the tuner). */
    fun stopPlaying(job: Job) {
        job.cancel()
        swapJob?.cancel()
        player.stop()
        opened?.let { p ->
            // Finished in the background even as the screen goes away (waiting for it here froze
            // the screen for ~2 s).
            MainScope().launch { attempt { Jdvr.backend.reportStopped(p) } }
        }
        opened = null
    }

    /**
     * A stream that freezes without an error (no new picture while it should be playing): after
     * 12 s, a fresh start. Paused (live TV pause), tuning or showing an error doesn't count.
     */
    suspend fun watchForStalls() {
        var lastPos = -1L
        var stillSince = 0L
        while (true) {
            delay(2000)
            val playing = player.playWhenReady && pausedAt == null && !tuning && showing
            val pos = player.currentPosition
            if (onHls && pos > 0) copyPlayedTo = pos
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
                return
            }
        }
    }

    /** After a good spell playing, the reconnect tries are back to three. */
    suspend fun forgiveReconnects() {
        delay(GOOD_SPELL_MS)
        if (error == null && !tuning) reconnects = 0
    }

    // Where a moment of the direct stream is in the HLS copy: both began at the live stream's edge
    // (or its start, for a fresh tune), the HLS copy this much later.
    private fun hlsPositionOf(directMs: Long): Long = (directMs - ((hlsStartedAt ?: directStartedAt) - directStartedAt)).coerceAtLeast(0)

    // How far behind live the picture is. Jellyfin's live HLS has no programme date-times, so the
    // player's own live offset is unknown: the copy's end (its duration so far) is live instead.
    fun behindLiveMs(): Long =
        if (onHls && Jdvr.backend.liveCopyIsFile) {
            // A buffer file from tune-in: its position is the time since then.
            (android.os.SystemClock.elapsedRealtime() - directStartedAt - player.currentPosition).coerceAtLeast(0)
        } else if (onHls) {
            player.currentLiveOffset.takeIf { it != C.TIME_UNSET }
                ?: player.duration.takeIf { it != C.TIME_UNSET }?.let { (it - player.currentPosition).coerceAtLeast(0) }
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

    private fun switchToHls(
        positionMs: Long,
        play: Boolean,
    ) {
        val url = copyUrl ?: return
        // Leaving the live stream: it stays open (NextPVR would drop the buffer with it).
        if (!onHls) Jdvr.backend.holdLive(true)
        // NextPVR's buffer is one file, opened anywhere: start (and rewind) on the full picture at or
        // before the moment, or the first pictures are broken (green while paused, a hitch on Play).
        if (Jdvr.backend.liveCopyIsFile) player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC)
        player.setMediaItem(MediaItem.fromUri(Jdvr.backend.liveCopyOpenUrl(url)), positionMs)
        player.playWhenReady = play
        player.prepare()
        onHls = true
        copyPlayedTo = positionMs
    }

    // A buffer file plays on past the size it was opened at, but seeks only within it: further on,
    // open it again there.
    private fun seekCopy(positionMs: Long) {
        val openedTo = player.duration
        if (Jdvr.backend.liveCopyIsFile && openedTo != C.TIME_UNSET && positionMs > openedTo - 1000) {
            switchToHls(positionMs, play = pausedAt == null)
        } else {
            player.seekTo(positionMs)
        }
    }

    fun rewind() {
        when {
            onHls -> seekCopy((player.currentPosition - 10_000).coerceAtLeast(0))
            canHls -> switchToHls(hlsPositionOf(player.currentPosition - 10_000), play = pausedAt == null)
            else -> ui.flash("Rewind isn't available on this channel.")
        }
        ui.showOsd()
    }

    // Back to live: the direct stream again (truly live, not the HLS copy's few seconds behind).
    fun toLive() {
        val p = playback ?: return
        if (onHls && copyUrl != p.url) {
            // NextPVR: a fresh live stream starts a fresh buffer (the old one goes with the held stream).
            Jdvr.backend.holdLive(false)
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
            player.setMediaItem(MediaItem.fromUri(p.url))
            player.playWhenReady = true
            player.prepare()
            directStartedAt = android.os.SystemClock.elapsedRealtime()
            onHls = false
            // A new buffer: where the old one ended says nothing about it.
            lastEnd = -1L
            copyPlayedTo = 0L
            if (Jdvr.backend.liveCopyIsFile) {
                // The old buffer is still listed for a few seconds before NextPVR deletes it.
                val old = copyUrl
                copyUrl = null
                hlsStartedAt = null
                scope.launch { findCopy(p, notThis = old) }
            }
        } else {
            player.seekToDefaultPosition()
            player.play()
        }
        pausedAt = null
        ui.showOsd()
    }

    /**
     * The player reached the end. The buffer file reads on as it grows, so this is it having stopped
     * growing for a while: open it again (or, within 10 s of live, go live; further back, going live
     * would skip programme). If it ends again at the same spot, the server let the tuner go: live again.
     */
    fun copyEnded() {
        if (!onHls || !Jdvr.backend.liveCopyIsFile) return
        val at = player.currentPosition.takeIf { it > 0 } ?: copyPlayedTo
        val stuck = lastEnd >= 0 && at >= lastEnd && at - lastEnd < 1000
        lastEnd = at
        val behind = android.os.SystemClock.elapsedRealtime() - directStartedAt - at
        if (stuck || behind <= 10_000) {
            lastEnd = -1L
            toLive()
        } else {
            switchToHls(at, play = pausedAt == null)
        }
    }

    fun forward() {
        if (!onHls) return
        // No further than live: within 30 s of it, back to the live stream itself.
        if (behindLiveMs() <= 30_000) toLive() else seekCopy(player.currentPosition + 30_000)
        ui.showOsd()
    }

    // Back to where you tuned in: the start of the HLS copy.
    fun backToTuneIn() {
        if (onHls) {
            player.seekTo(0)
        } else if (canHls) {
            switchToHls(0, play = pausedAt == null)
        }
        ui.showOsd()
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
        ui.showOsd()
    }

    fun playPause() = if (pausedAt == null) pause() else resume()
}
