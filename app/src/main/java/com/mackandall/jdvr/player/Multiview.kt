package com.mackandall.jdvr.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.MultiviewRefused
import com.mackandall.jdvr.data.MultiviewServer
import com.mackandall.jdvr.data.MultiviewSessionDto
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** Who makes the pictures: this TV (a player per picture) or the server (one combined stream). */
internal enum class MultiviewMode { TV, SERVER }

/** Equal pictures, or the first one big with the others in a column beside it. */
internal enum class MultiviewLayout(
    val wire: String,
) { GRID("grid"), BIG("big") }

/** Where a picture sits, as fractions of the combined frame (the same frames the server makes). */
internal data class TileBox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

/** The frame for [count] pictures: its aspect (width / height) and where each picture sits. Mirrors the server's MosaicLayout. */
internal fun multiviewFrame(
    layout: MultiviewLayout,
    count: Int,
): Pair<Float, List<TileBox>> {
    fun px(
        w: Int,
        h: Int,
        vararg r: IntArray,
    ) = (w.toFloat() / h) to r.map { TileBox(it[0].toFloat() / w, it[1].toFloat() / h, it[2].toFloat() / w, it[3].toFloat() / h) }
    if (count <= 1) return px(1920, 1080, intArrayOf(0, 0, 1920, 1080))
    if (layout == MultiviewLayout.BIG) {
        val tiles = mutableListOf(intArrayOf(0, 0, 1440, 810))
        for (i in 1 until count) tiles += intArrayOf(1440, (i - 1) * 270, 480, 270)
        return px(1920, 810, *tiles.toTypedArray())
    }
    return when (count) {
        2 -> px(1920, 540, intArrayOf(0, 0, 960, 540), intArrayOf(960, 0, 960, 540))
        3 -> px(1920, 720, intArrayOf(0, 0, 1280, 720), intArrayOf(1280, 0, 640, 360), intArrayOf(1280, 360, 640, 360))
        else -> px(1920, 1080, intArrayOf(0, 0, 960, 540), intArrayOf(960, 0, 960, 540), intArrayOf(0, 540, 960, 540), intArrayOf(960, 540, 960, 540))
    }
}

/**
 * Multiview: up to four live channels at once, with a white ring on one; its sound plays and the
 * remote's channel keys act on it. On this TV each picture is its own player (its own stream, or
 * the channel's recording when it's being recorded: no tuner); on the server, JellyDVR Live opens
 * the channels and combines them into one stream for TVs that can't decode several at once.
 */
@OptIn(UnstableApi::class)
internal object Multiview {
    var active by mutableStateOf(false)
        private set
    var mode by mutableStateOf(MultiviewMode.TV)
        private set
    var layout by mutableStateOf(MultiviewLayout.GRID)
        private set

    /** The channels shown, in frame order (in BIG the first is the big one). */
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var ring by mutableIntStateOf(0)
        private set

    // This TV: a picture per channel, in [channels] order.
    var pictures by mutableStateOf<List<ExtraPicture>>(emptyList())
        private set

    // The server: its session and the player of the combined stream.
    var session by mutableStateOf<MultiviewSessionDto?>(null)
        private set
    var serverPlayer by mutableStateOf<ExoPlayer?>(null)
        private set

    // Opening or changing the server's stream (it takes a few seconds), and what went wrong.
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    // Debug: a stand-in server to start against (address, pictures), and whether this is that test.
    var pendingTest by mutableStateOf<Pair<String, Int>?>(null)
    var testing = false
        private set

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ui: LiveOverlays? = null
    private var appContext: Context? = null
    private var giveWayJob: Job? = null

    /** Messages over the pictures. */
    val overlays: LiveOverlays get() = ui ?: LiveOverlays(scope).also { ui = it }

    /** How many pictures this TV shows itself (Settings › Multiview › Most pictures; automatic: by model). */
    val tvMax: Int
        get() =
            Jdvr.prefs.multiviewMax.takeIf { it in 1..4 } ?: if (android.os.Build.MODEL.contains("SHIELD", ignoreCase = true)) 4 else 2

    val max: Int get() = if (mode == MultiviewMode.SERVER) 4 else tvMax

    val ringed: Channel? get() = channels.getOrNull(ring)

    /** Debug: Multiview of [list] made by a stand-in server at [base]; never tunes (no fallback to this TV, no give-way). */
    fun startTest(
        context: Context,
        base: String,
        list: List<Channel>,
    ) {
        close()
        MultiviewServer.testBase = base
        testing = true
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ui = LiveOverlays(scope)
        error = null
        layout = MultiviewLayout.GRID
        channels = list
        ring = 0
        mode = MultiviewMode.SERVER
        active = true
        apply()
    }

    /** Starts Multiview with [first] (and [second], the picture in picture's channel, if any). */
    fun start(
        context: Context,
        first: Channel,
        second: Channel?,
    ) {
        close()
        appContext = context.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ui = LiveOverlays(scope)
        error = null
        layout = MultiviewLayout.GRID
        channels = listOfNotNull(first, second?.takeIf { it.id != first.id })
        ring = 0
        active = true
        scope.launch {
            mode = chooseMode()
            if (mode == MultiviewMode.TV) channels = channels.take(tvMax)
            apply()
        }
        watchGiveWay()
    }

    // Settings › Multiview › Pictures made by. Automatic: the server for TVs that can't play
    // several pictures themselves (all but the Shield, so far), when the server can.
    private suspend fun chooseMode(): MultiviewMode {
        if (testing) return MultiviewMode.SERVER
        val want =
            when (Jdvr.prefs.multiviewMode) {
                Prefs.MV_TV -> return MultiviewMode.TV
                Prefs.MV_SERVER -> MultiviewMode.SERVER
                else -> if (android.os.Build.MODEL.contains("SHIELD", ignoreCase = true)) MultiviewMode.TV else MultiviewMode.SERVER
            }
        if (want == MultiviewMode.SERVER) {
            val cap = attempt { MultiviewServer.capability() }
            if (cap?.available == true) return MultiviewMode.SERVER
            overlays.flash(cap?.reason?.takeIf { it.isNotBlank() }?.let { "Server Multiview isn't available: $it" } ?: "Server Multiview isn't available: this TV shows the pictures itself.", 6000)
        }
        return MultiviewMode.TV
    }

    /** Switch who makes the pictures (the tile menu). */
    fun switchMode(to: MultiviewMode) {
        if (to == mode || !active) return
        if (testing) {
            overlays.flash("Test with the stand-in server: the server makes the pictures.", 4000)
            return
        }
        scope.launch {
            if (to == MultiviewMode.SERVER) {
                val cap = attempt { MultiviewServer.capability() }
                if (cap?.available != true) {
                    overlays.flash(cap?.reason?.takeIf { it.isNotBlank() } ?: "The server can't make Multiview.", 5000)
                    return@launch
                }
                closeTvPictures()
            } else {
                closeServer()
                channels = channels.take(tvMax)
                ring = ring.coerceAtMost(channels.lastIndex)
            }
            mode = to
            apply()
        }
    }

    // ---- what's on screen ----------------------------------------------------------------------

    fun moveRing(to: Int) {
        if (to !in channels.indices || to == ring) return
        ring = to
        applySound()
    }

    /** OK on a picture: grid → that one big; big → the ringed one big, or back to the grid. */
    fun okOnRing() {
        if (channels.size < 2) return
        if (layout == MultiviewLayout.BIG && ring == 0) {
            layout = MultiviewLayout.GRID
            apply()
        } else {
            makeBig(ring)
        }
    }

    fun makeBig(i: Int) {
        if (i !in channels.indices) return
        val list = channels.toMutableList()
        val c = list.removeAt(i)
        list.add(0, c)
        reorder(list)
        ring = 0
        layout = MultiviewLayout.BIG
        apply()
    }

    fun add(c: Channel) {
        if (channels.size >= max || channels.any { it.id == c.id }) return
        channels = channels + c
        ring = channels.lastIndex
        apply()
    }

    fun replace(
        i: Int,
        c: Channel,
    ) {
        if (i !in channels.indices || channels.any { it.id == c.id }) return
        channels = channels.toMutableList().also { it[i] = c }
        apply()
    }

    fun remove(i: Int) {
        if (channels.size <= 1 || i !in channels.indices) return
        channels = channels.toMutableList().also { it.removeAt(i) }
        ring = ring.coerceAtMost(channels.lastIndex)
        if (channels.size == 1) layout = MultiviewLayout.GRID
        apply()
    }

    // Pictures keep their players when they move (this TV); the order is the frame's.
    private fun reorder(list: List<Channel>) {
        if (mode == MultiviewMode.TV) {
            pictures = list.mapNotNull { c -> pictures.firstOrNull { it.channel.id == c.id } }
        }
        channels = list
    }

    /** Brings the pictures in line with [channels] and [layout]. */
    private fun apply() {
        if (!active) return
        when (mode) {
            MultiviewMode.TV -> applyTv()
            MultiviewMode.SERVER -> applyServer()
        }
    }

    private fun applyTv() {
        val ctx = appContext ?: return
        val keep = pictures.filter { p -> channels.any { it.id == p.channel.id } }
        pictures.filter { it !in keep }.forEach { it.close() }
        pictures =
            channels.mapIndexed { i, c ->
                keep.firstOrNull { it.channel.id == c.id }
                    ?: ExtraPicture(ctx, c, slot = 10 + i, ui = overlays, lean = true, report = { reportable(c.id) }).also { it.begin() }
            }
        applySound()
    }

    // The ringed and the big picture count as watched (JellyDVR Live buffers those); the rest don't.
    private fun reportable(id: UUID): Boolean = ringed?.id == id || (layout == MultiviewLayout.BIG && channels.firstOrNull()?.id == id)

    private var serverJob: Job? = null

    private fun applyServer() {
        val ids = channels.map { it.id }
        val lay = if (channels.size < 2) MultiviewLayout.GRID.wire else layout.wire
        serverJob?.cancel()
        serverJob =
            scope.launch {
                busy = if (session == null) "Starting Multiview on the server…" else "Changing pictures…"
                try {
                    val s = session?.let { MultiviewServer.change(it.id, ids, lay) } ?: MultiviewServer.start(ids, lay)
                    session = s
                    val p = serverPlayer ?: serverPlayerOf()
                    p.setMediaItem(MediaItem.fromUri(MultiviewServer.streamUrl(s)))
                    p.playWhenReady = true
                    p.prepare()
                    applySound()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Server Multiview")
                    if (testing) {
                        overlays.flash("Stand-in server: ${e.message}", 8000)
                        return@launch
                    }
                    val inUse = attempt { MultiviewServer.capability() }?.inUse.orEmpty().filter { it != MultiviewServer.deviceName() }
                    val why =
                        when {
                            inUse.isNotEmpty() -> "Server Multiview is in use on ${inUse.joinToString()}."
                            e is MultiviewRefused -> e.message ?: "The server couldn't start Multiview."
                            else -> "The server couldn't start Multiview."
                        }
                    // This TV takes over, with as many pictures as it can show itself.
                    closeServer()
                    mode = MultiviewMode.TV
                    channels = channels.take(tvMax)
                    ring = ring.coerceAtMost(channels.lastIndex)
                    overlays.flash("$why This TV shows up to $tvMax pictures itself.", 7000)
                    applyTv()
                } finally {
                    busy = null
                }
            }
    }

    private fun serverPlayerOf(): ExoPlayer {
        val p = livePlayer(appContext!!, readTimeoutSec = 30)
        // Debug builds: the picture's frame counts every 5 s (logcat tag Multiview), to see whether a TV keeps up.
        if (com.mackandall.jdvr.BuildConfig.DEBUG) {
            scope.launch {
                while (isActive) {
                    delay(5000)
                    val c = p.videoDecoderCounters ?: continue
                    c.ensureUpdated()
                    android.util.Log.i("Multiview", "rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} maxConsecDrop=${c.maxConsecutiveDroppedBufferCount} decoder=${p.videoFormat?.let { "${it.width}x${it.height}" }} audioTrack=$ring")
                }
            }
        }
        p.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    applySound()
                }

                override fun onPlayerError(e: PlaybackException) {
                    Timber.w(e, "Server Multiview stream")
                    // The stream broke (the server restarted ffmpeg, the network dropped): open it again.
                    scope.launch {
                        delay(2000)
                        val s = session ?: return@launch
                        p.setMediaItem(MediaItem.fromUri(MultiviewServer.streamUrl(s)))
                        p.prepare()
                    }
                }
            },
        )
        serverPlayer = p
        return p
    }

    /** The ringed picture's sound; the others silent (no audio decoding at all for them). */
    fun applySound() {
        when (mode) {
            MultiviewMode.TV -> pictures.forEachIndexed { i, p -> p.setAudible(i == ring) }
            MultiviewMode.SERVER -> {
                val p = serverPlayer ?: return
                val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val track = session?.tiles?.getOrNull(ring)?.audioTrack ?: ring
                val group = groups.getOrNull(track) ?: return
                p.trackSelectionParameters =
                    p.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
            }
        }
    }

    // ---- leaving -------------------------------------------------------------------------------

    /** Leaves Multiview: everything stops; the ringed channel's id, to watch full screen. */
    fun leave(): UUID? {
        val keep = ringed?.id?.takeIf { !testing }
        close()
        return keep
    }

    fun close() {
        giveWayJob?.cancel()
        serverJob?.cancel()
        closeTvPictures()
        closeServer()
        if (active) scope.cancel()
        active = false
        channels = emptyList()
        busy = null
        testing = false
        MultiviewServer.testBase = null
    }

    private fun closeTvPictures() {
        pictures.forEach { it.close() }
        pictures = emptyList()
    }

    private fun closeServer() {
        serverPlayer?.release()
        serverPlayer = null
        val base = MultiviewServer.testBase
        session?.let { s -> MainScope().launch { attempt { MultiviewServer.stop(s.id, base) } } }
        session = null
    }

    /**
     * A recording about to start with every tuner busy: the last picture that holds a tuner (not
     * the ringed one) closes, so the recording gets its tuner.
     */
    private fun watchGiveWay() {
        giveWayJob?.cancel()
        giveWayJob =
            scope.launch {
                while (isActive) {
                    delay(15_000)
                    if (channels.size < 2) continue
                    val now = LocalDateTime.now()
                    val soon =
                        attempt { Jdvr.backend.schedule() }.orEmpty().firstOrNull { u ->
                            val start = runCatching { java.time.OffsetDateTime.parse(u.start).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
                            start != null && LivePictures.startsSoon(start, now)
                        } ?: continue
                    if (attempt { Jdvr.backend.tuners(null) }?.full != true) continue
                    val victim =
                        when (mode) {
                            MultiviewMode.TV -> pictures.indices.lastOrNull { it != ring && pictures[it].holdsTuner }
                            MultiviewMode.SERVER -> channels.indices.lastOrNull { it != ring }
                        } ?: continue
                    val gone = channels[victim]
                    remove(victim)
                    overlays.flash("${gone.number} ${gone.name} closed: ${soon.name} needs its tuner to record", 6000)
                }
            }
    }
}
