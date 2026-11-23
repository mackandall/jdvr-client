package com.mackandall.jdvr.player

import android.content.Context
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LivePlayback
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** Sparse-PCR recordings needed an 8 MB TS search window to seek (the Wholphin fix). */
private const val TS_SEARCH_BYTES = 8 * 1024 * 1024

@OptIn(UnstableApi::class)
internal fun livePlayer(
    context: Context,
    // Several pictures at once (Multiview): each keeps at most 15 s / 16 MB ahead. The default
    // (50 s) ran a Shield out of memory at three recordings at once (2026-11-22 test).
    lean: Boolean = false,
    // How long to wait for the first bytes: the server's combined Multiview stream starts only
    // once its ffmpeg has opened every channel (4 live channels took over 10 s, 2026-11-22).
    readTimeoutSec: Long? = null,
): ExoPlayer {
    val client = readTimeoutSec?.let { Jdvr.http.newBuilder().readTimeout(it, java.util.concurrent.TimeUnit.SECONDS).build() } ?: Jdvr.http
    val extractors = DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TS_SEARCH_BYTES)
    return ExoPlayer
        .Builder(context, renderersFactory(context))
        .apply {
            if (lean) {
                setLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl
                        .Builder()
                        .setBufferDurationsMs(5_000, 15_000, 1_000, 2_000)
                        .setTargetBufferBytes(16 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build(),
                )
            }
        }
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(GrowingFileDataSource.Factory(OkHttpDataSource.Factory(client)), extractors)
                .setLoadErrorHandlingPolicy(NoRetryOnServerError),
        )
        // A growing file plays on past the length it was opened at, on purpose: Media3 1.11 took
        // that for a stuck player after 60 s and stopped it with an error (2026-11-18).
        .setStuckPlayingNotEndingTimeoutMs(Int.MAX_VALUE)
        .build()
        .apply {
            playWhenReady = true
            applySubtitlePreference(this)
        }
}

/**
 * The live channel being watched, kept apart from the full-screen player so it can go on playing
 * after Back (in the guide's details picture). It owns the ExoPlayer and the channel's
 * [LiveStream]; screens only show it. [close] stops the stream (frees the tuner) and the player.
 */
@Stable
internal class MainPicture(
    context: Context,
    channelId: UUID,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ui = LiveOverlays(scope)
    val player: ExoPlayer = livePlayer(context)

    var stream by mutableStateOf(LiveStream(channelId, player, scope, ui))
        private set
    val channelId: UUID get() = stream.channelId

    var closed = false
        private set
    private var job: Job? = null

    private val listener =
        object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Timber.w(e, "Playback error")
                val s = stream
                if (!s.reconnect("the stream stopped (${e.errorCodeName})")) {
                    s.error = liveErrorText(e)
                    s.tuning = false
                }
            }

            override fun onRenderedFirstFrame() {
                stream.tuning = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) stream.copyEnded()
            }
        }
    private val logger = playerLog("live", player)

    init {
        player.addListener(listener)
        player.addListener(logger)
        // Each channel, and each fresh start of it (Try again, reconnecting), plays until the next.
        scope.launch {
            snapshotFlow { stream to stream.attempt }.collectLatest { (s, _) ->
                val started = s.start()
                job = started
                val stalls = launch { s.watchForStalls() }
                val forgive = launch { s.forgiveReconnects() }
                try {
                    awaitCancellation()
                } finally {
                    stalls.cancel()
                    forgive.cancel()
                    if (!closed) {
                        s.stopPlaying(started)
                        // A new channel: the old one's stream is let go (a retry keeps it held).
                        if (stream !== s) Jdvr.backend.holdLive(false)
                    }
                }
            }
        }
    }

    /** Another channel in the same picture (▲▼, digits, the mini guide, Watch here in the guide). */
    fun changeChannel(id: UUID) {
        if (closed || id == channelId) return
        stream = LiveStream(id, player, scope, ui)
    }

    /** Sound: full screen at full volume; in the guide as Settings › Guide says. */
    fun setVolume(v: Float) {
        player.volume = v
        // Silent: no sound track at all. Dolby Digital passed through to a receiver ignores the
        // volume, so after Swap it went on playing beside the big picture's sound.
        val off = v == 0f
        val params = player.trackSelectionParameters
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes != off) {
            player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, off).build()
        }
    }

    /**
     * [free]: done with live TV (Back out of it, left the guide or the app), so JellyDVR Live lets
     * the tuner go now rather than a minute after the last viewer, as Stop does. Not when a
     * recording of it opens next (rewind into the buffer, watch from the start): that needs it.
     */
    fun close(free: Boolean = true) {
        if (closed) return
        closed = true
        scope.cancel()
        job?.let { stream.stopPlaying(it) }
        Jdvr.backend.holdLive(false)
        if (free) {
            Jdvr.backend.stopBuffering(channelId)
        }
        player.removeListener(listener)
        player.removeListener(logger)
        player.release()
    }
}

/** Where a second picture's stream comes from. */
internal sealed interface PictureSource {
    /** Its own live stream (a tuner of its own). */
    data object Tune : PictureSource

    /** The channel's recording, played at its live edge: no tuner of its own. */
    data class Recording(
        val itemId: UUID,
    ) : PictureSource
}

/** What a second picture is doing. */
internal enum class PictureState { OPENING, PLAYING, RECORDING_STOPPED, FAILED }

/**
 * A second picture (picture in picture; multiview later), silent while small (its sound track off:
 * no audio decoding at all). A channel that's recording plays from the recording, so it needs no
 * tuner; otherwise it opens a stream of its own, run like the main picture's ([LiveStream]: reconnects,
 * and on Jellyfin pause and rewind). Swapped big, the remote's pause and rewind act on [transport].
 */
@Stable
internal class ExtraPicture(
    context: Context,
    val channel: Channel,
    private val slot: Int,
    private val ui: LiveOverlays,
    lean: Boolean = false,
    // Multiview: tell the server it's watched (so JellyDVR Live buffers it) only once this says so.
    private val report: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val player: ExoPlayer =
        livePlayer(context, lean).apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
        }
    val channelId: UUID get() = channel.id

    var source by mutableStateOf<PictureSource?>(null)
        private set

    // Its own tuner: the stream, as the main picture runs its own.
    private var live by mutableStateOf<LiveStream?>(null)
    private var liveJob: Job? = null

    // Playing from the recording: what it's doing (its own stream says that itself).
    private var recState by mutableStateOf(PictureState.OPENING)
    private var recMessage by mutableStateOf<String?>(null)

    val state: PictureState
        get() =
            live?.let { s ->
                when {
                    s.busy != null || s.error != null -> PictureState.FAILED
                    s.tuning -> PictureState.OPENING
                    else -> PictureState.PLAYING
                }
            } ?: recState
    val message: String?
        get() = live?.let { s -> s.busy?.let { "All tuners are busy." } ?: s.error } ?: recMessage

    // Whose turn it is to have the sound (swapped to the big picture: the extra plays with sound).
    var audible = false
        private set

    var closed = false
        private set
    private var seekedToEdge = false

    // Playing from the recording: where its live edge was when last opened (length, at what clock).
    private var edgeMs = 0L
    private var edgeAt = 0L

    private val listener =
        object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Timber.w(e, "Picture %s: playback error", channel.number)
                if (closed) return
                live?.let { s ->
                    if (!s.reconnect("the stream stopped (${e.errorCodeName})")) {
                        s.error = "The picture stopped. A recording may have needed its tuner."
                        s.tuning = false
                    }
                    return
                }
                (source as? PictureSource.Recording)?.let { afterEnd(it.itemId, player.currentPosition) }
            }

            override fun onRenderedFirstFrame() {
                live?.let { it.tuning = false } ?: run { if (recState == PictureState.OPENING) recState = PictureState.PLAYING }
            }

            override fun onPlaybackStateChanged(s: Int) {
                live?.let { stream ->
                    if (s == Player.STATE_ENDED) stream.copyEnded()
                    return
                }
                val src = source as? PictureSource.Recording ?: return
                if (s == Player.STATE_READY) {
                    player.duration.takeIf { it != C.TIME_UNSET }?.let { d ->
                        edgeMs = d
                        edgeAt = android.os.SystemClock.elapsedRealtime()
                    }
                    if (!seekedToEdge) {
                        // A recording opens at its start: the live edge is its end.
                        seekedToEdge = true
                        player.duration.takeIf { it != C.TIME_UNSET && it > 8000 }?.let { player.seekTo(it - 4000) }
                    }
                } else if (s == Player.STATE_ENDED) {
                    // It reads on as the recording grows: ending means it stopped growing.
                    afterEnd(src.itemId, player.currentPosition)
                }
            }
        }
    private val logger = playerLog("picture ${channel.number}", player)

    init {
        player.addListener(listener)
        player.addListener(logger)
        // Its own stream, and each fresh start of it (reconnecting), plays until the next.
        scope.launch {
            snapshotFlow { live to live?.attempt }.collectLatest { (s, _) ->
                if (s == null) return@collectLatest
                val started = s.start()
                liveJob = started
                val stalls = launch { s.watchForStalls() }
                val forgive = launch { s.forgiveReconnects() }
                try {
                    awaitCancellation()
                } finally {
                    stalls.cancel()
                    forgive.cancel()
                    // A retry: the last start's stream goes (stopTune stops the current one itself).
                    if (!closed && live === s) s.stopPlaying(started)
                }
            }
        }
    }

    /** Starts it: from the channel's recording when one is being made (unless [live]), else its own stream. */
    private fun start(live: Boolean = false): Job =
        scope.launch {
            recState = PictureState.OPENING
            recMessage = null
            seekedToEdge = false
            recPausedAt = null
            try {
                val rec = if (live) null else LivePictures.recordingNow(channel)
                if (rec != null) {
                    val p = Jdvr.backend.startItem(rec)
                    source = PictureSource.Recording(rec)
                    lastEnd = -1L
                    player.setMediaItem(mediaItemOf(p).buildUpon().setUri(Jdvr.backend.followingUrl(p.url)).build())
                    player.playWhenReady = true
                    player.prepare()
                } else {
                    source = PictureSource.Tune
                    this@ExtraPicture.live = LiveStream(channelId, player, scope, ui, slot, report)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't start picture %s", channel.number)
                recState = PictureState.FAILED
                recMessage = "The server couldn't start ${channel.number} ${channel.name}."
            }
        }

    // Where the recording last ended: ending at the same spot again means it stopped growing (the
    // recording is over or was stopped), whatever the server (no plugin needed to ask).
    private var lastEnd = -1L

    private fun recordingStopped() {
        if (recState == PictureState.RECORDING_STOPPED || closed) return
        recState = PictureState.RECORDING_STOPPED
        player.pause()
    }

    // The recording opened again at [atMs] (null: at its live edge), as long as it is now.
    private fun reopen(
        itemId: UUID,
        atMs: Long?,
    ) {
        scope.launch {
            attempt { Jdvr.backend.startItem(itemId) }?.let { p ->
                val item = mediaItemOf(p).buildUpon().setUri(Jdvr.backend.followingUrl(p.url)).build()
                if (atMs == null) {
                    seekedToEdge = false
                    player.setMediaItem(item)
                } else {
                    player.setMediaItem(item, atMs)
                }
                player.prepare()
            }
        }
    }

    /**
     * The recording ended or its stream broke. Still recording: open it again where it was. Over, with
     * another recording of the channel going on (a manual one overlapping, or the next programme's):
     * follow that one. Else the "recording stopped" choice.
     */
    private fun afterEnd(
        itemId: UUID,
        atMs: Long,
    ) {
        if (lastEnd >= 0 && atMs - lastEnd < 2000) {
            recordingStopped()
            return
        }
        lastEnd = atMs
        scope.launch {
            delay(2000)
            val still = attempt { Jdvr.backend.liveSegments(itemId).first } ?: false
            if (still) {
                reopen(itemId, atMs)
                return@launch
            }
            val next = LivePictures.recordingNow(channel)
            if (next != null && next != itemId) {
                Timber.tag("JDVRPlayer").i("picture %s: recording over, following the channel's next one", channel.number)
                begin()
            } else {
                recordingStopped()
            }
        }
    }

    // Playing from the recording: paused, and the remote's controls on it (it seeks in the file).
    private var recPausedAt by mutableStateOf<java.time.Instant?>(null)

    private val recording =
        object : LiveTransport {
            override val channelId get() = channel.id
            override val pausedAt get() = recPausedAt
            override val showing get() = recState != PictureState.FAILED
            override val tuning get() = recState == PictureState.OPENING
            override val error get() = recMessage.takeIf { recState == PictureState.FAILED }
            override val onHls get() = behindLiveMs() > 10_000

            // Its live edge: the length when opened, plus the time since (it grows as it records).
            private fun edge(): Long = if (edgeAt == 0L) player.duration.coerceAtLeast(0) else edgeMs + (android.os.SystemClock.elapsedRealtime() - edgeAt)

            override fun behindLiveMs(): Long = recordingBehind(edge(), player.currentPosition)

            override fun bufferFrom(behind: Long): LocalDateTime? = LocalDateTime.now().minusNanos((behind + player.currentPosition) * 1_000_000)

            override fun pause() {
                if (recPausedAt != null) return
                recPausedAt = java.time.Instant.now()
                player.pause()
            }

            override fun resume() {
                if (recPausedAt == null) return
                recPausedAt = null
                player.play()
                ui.showOsd()
            }

            override fun rewind() {
                player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                ui.showOsd()
            }

            override fun forward() {
                val to = recordingForward(edge(), player.currentPosition)
                if (to == null) toLive() else seekWithin(to)
                ui.showOsd()
            }

            override fun toLive() {
                val rec = source as? PictureSource.Recording ?: return
                recPausedAt = null
                player.playWhenReady = true
                reopen(rec.itemId, null)
                ui.showOsd()
            }

            override fun backToTuneIn() {
                player.seekTo(0)
                ui.showOsd()
            }
        }

    // Further than it was opened to: opened again there (it seeks only within the length it opened at).
    private fun seekWithin(to: Long) {
        val rec = source as? PictureSource.Recording ?: return
        val openedTo = player.duration
        if (openedTo != C.TIME_UNSET && to > openedTo - 1000) reopen(rec.itemId, to) else player.seekTo(to)
    }

    /** What the remote's pause and rewind act on while this is the big picture. */
    val transport: LiveTransport get() = live ?: recording

    private var startJob: Job? = null

    fun begin(live: Boolean = false) {
        startJob?.cancel()
        stopTune()
        startJob = start(live)
    }

    // Lets go of its own live stream (frees the tuner).
    private fun stopTune() {
        live?.let { s ->
            live = null
            liveJob?.let { s.stopPlaying(it) }
            liveJob = null
            // Jellyfin: JellyDVR Live buffers a watched channel and lets the tuner go only a minute
            // after the last viewer; as Stop does for the big picture, now (it leaves real
            // recordings, kept buffers and channels someone else watches).
            Jdvr.backend.stopBuffering(channelId)
        }
        source = null
        player.stop()
    }

    /** Has the sound (it's the big picture after Swap) or not. */
    fun setAudible(on: Boolean) {
        audible = on
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on).build()
        player.volume = if (on) 1f else 0f
    }

    val holdsTuner get() = source is PictureSource.Tune

    fun close() {
        if (closed) return
        startJob?.cancel()
        stopTune()
        closed = true
        scope.cancel()
        player.removeListener(listener)
        player.removeListener(logger)
        player.release()
    }
}

/** How far behind its live edge a recording plays. */
internal fun recordingBehind(
    edgeMs: Long,
    positionMs: Long,
): Long = (edgeMs - positionMs).coerceAtLeast(0)

/** 30 s ahead in a recording, or null within 30 s of its live edge (go live instead). */
internal fun recordingForward(
    edgeMs: Long,
    positionMs: Long,
): Long? = if (recordingBehind(edgeMs, positionMs) <= 30_000) null else positionMs + 30_000

/** A recording about to start that needs the tuner a second picture holds. */
internal data class GiveWay(
    val timerId: String,
    val name: String,
    val start: LocalDateTime,
    val closesAt: Long,
)

/**
 * The live pictures: the channel being watched ([main]), which outlives the full-screen player, and
 * a second picture ([extra], picture in picture). Multiview will hold more than one extra.
 */
internal object LivePictures {
    var main by mutableStateOf<MainPicture?>(null)
        private set
    var extra by mutableStateOf<ExtraPicture?>(null)
        private set

    // Swapped: the extra shows big (with the sound), the main picture small.
    var swapped by mutableStateOf(false)
        private set
    var giveWay by mutableStateOf<GiveWay?>(null)
        private set
    private var giveWayJob: Job? = null

    /** Watch [channelId]: the picture already playing it, the same picture on a new channel, or a new one. */
    fun watch(
        context: Context,
        channelId: UUID,
    ): MainPicture {
        main?.takeIf { !it.closed }?.let { m ->
            if (extra?.channelId == channelId) closeExtra()
            m.changeChannel(channelId)
            return m
        }
        return MainPicture(context.applicationContext, channelId).also { main = it }
    }

    /** Stops everything: the stream, the tuner (unless not [free]: see [MainPicture.close]), the second picture. */
    fun closeAll(free: Boolean = true) {
        closeExtra()
        main?.close(free)
        main = null
    }

    // ---- picture in picture -------------------------------------------------------------------

    /** How many videos this device can decode at once (the fewest any of its H.264 / MPEG-2 decoders allows). */
    val decoders: Int by lazy {
        runCatching {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            listOf("video/avc", "video/mpeg2")
                .mapNotNull { mime ->
                    infos
                        .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                        .maxOfOrNull { it.getCapabilitiesForType(mime).maxSupportedInstances }
                }.minOrNull() ?: 1
        }.getOrDefault(1)
    }

    val canPip get() = decoders >= 2

    /** The recording being made of the channel's programme on now (not a live TV buffer), or null. */
    suspend fun recordingNow(channel: Channel): UUID? {
        // By channel first: fresh (the channel list can be minutes old) and finds manual recordings too.
        attempt { Jdvr.backend.recordingOnChannel(channel.id) }?.let { return it }
        val now = channel.now ?: return null
        if (!now.isRecording || !now.isOn()) return null
        val buffers = attempt { Jdvr.backend.bufferPrograms() }.orEmpty()
        if (now.id.toString().replace("-", "").lowercase() in buffers.map { it.replace("-", "").lowercase() }) return null
        return attempt { Jdvr.backend.recordingOfProgram(now.id) }
    }

    fun openExtra(
        context: Context,
        channel: Channel,
    ) {
        val m = main ?: return
        closeExtra()
        extra = ExtraPicture(context.applicationContext, channel, slot = 1, ui = m.ui).also { it.begin() }
        watchGiveWay()
    }

    /**
     * Another channel in the second picture, keeping its place (swapped: it's the big picture, and
     * ▲▼, digits and the mini guide change it). The main picture's own channel: back to one picture.
     */
    fun changeExtra(
        context: Context,
        channel: Channel,
    ) {
        val m = main ?: return
        val e = extra ?: return
        if (channel.id == e.channelId) return
        if (channel.id == m.channelId) {
            closeExtra()
            return
        }
        val big = swapped
        e.close()
        extra =
            ExtraPicture(context.applicationContext, channel, slot = 1, ui = m.ui).also {
                if (big) it.setAudible(true)
                it.begin()
            }
        watchGiveWay()
    }

    fun closeExtra() {
        giveWayJob?.cancel()
        giveWay = null
        if (swapped) swap()
        extra?.close()
        extra = null
    }

    /** The big and small pictures change places; the sound goes with the big one. */
    fun swap() {
        val m = main ?: return
        val e = extra ?: return
        swapped = !swapped
        e.setAudible(swapped)
        m.setVolume(if (swapped) 0f else 1f)
    }

    /** The recording that stopped under the picture: go on live (its own tuner). */
    fun extraLive() {
        extra?.begin(live = true)
        watchGiveWay()
    }

    /**
     * While the second picture holds a tuner: a recording about to start with every tuner busy
     * closes it (with 30 s warning), unless the recording is cancelled.
     */
    private fun watchGiveWay() {
        giveWayJob?.cancel()
        giveWayJob =
            MainScope().launch {
                while (isActive) {
                    delay(15_000)
                    val e = extra ?: return@launch
                    if (giveWay != null) continue
                    if (!e.holdsTuner) continue
                    val soon = attempt { Jdvr.backend.schedule() }.orEmpty().firstNotNullOfOrNull { u -> parseStart(u.start)?.takeIf { s -> startsSoon(s, LocalDateTime.now()) }?.let { u to it } } ?: continue
                    val tuners = attempt { Jdvr.backend.tuners(null) } ?: continue
                    if (!tuners.full) continue
                    giveWay = GiveWay(soon.first.timerId, soon.first.name, soon.second, System.currentTimeMillis() + 30_000)
                }
            }.also { job ->
                // The countdown itself, a second at a time.
                MainScope().launch {
                    while (job.isActive) {
                        delay(1000)
                        val gw = giveWay ?: continue
                        if (System.currentTimeMillis() >= gw.closesAt) {
                            closeExtra()
                            main?.ui?.flash("Picture in picture closed: ${gw.name} needed its tuner", 5000)
                            break
                        }
                    }
                }
            }
    }

    fun giveWayNow() {
        val name = giveWay?.name
        closeExtra()
        name?.let { main?.ui?.flash("Picture closed: $it will record", 4000) }
    }

    fun skipRecording() {
        val gw = giveWay ?: return
        giveWay = null
        MainScope().launch {
            val ok = attempt { Jdvr.backend.cancelRecording(gw.timerId) } != null
            main?.ui?.flash(if (ok) "${gw.name} won't record" else "Couldn't cancel ${gw.name}", 4000)
        }
    }

    private fun parseStart(s: String): LocalDateTime? =
        runCatching {
            java.time.OffsetDateTime
                .parse(s)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        }.recoverCatching { LocalDateTime.parse(s.removeSuffix("Z")) }.getOrNull()

    // A recording starting within the next minute and a half (or started in the last half minute).
    internal fun startsSoon(
        start: LocalDateTime,
        now: LocalDateTime,
    ): Boolean = !start.isBefore(now.minusSeconds(30)) && !start.isAfter(now.plusSeconds(90))
}

/** A live picture's video, without controls, for any surface (the guide's box, the PiP corner). */
@OptIn(UnstableApi::class)
@Composable
internal fun LivePictureView(
    player: Player,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // Never the remote's: with a second picture on screen, D-pad focus went into one
                // and the controls lost theirs (no button lit, OK did nothing).
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Switching to the HLS copy keeps the last frame up instead of flashing black.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                if (fill) resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
        update = { if (it.player !== player) it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** The guide's sound for the picture playing there (Settings › Guide › Sound in the guide). */
internal fun guideVolume(): Float =
    when (Jdvr.prefs.guideSound) {
        Prefs.ON -> 1f
        Prefs.MUTE -> 0f
        else -> 0.4f
    }

/** The border Settings › Picture in picture › Border asks for round a small live picture (none when off). */
internal fun Modifier.pictureBorder(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val width =
        when (Jdvr.prefs.pictureBorder) {
            com.mackandall.jdvr.data.Prefs.THIN_BORDER -> 2.dp
            com.mackandall.jdvr.data.Prefs.MEDIUM_BORDER -> 4.dp
            else -> return this
        }
    return border(width, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), shape)
}
