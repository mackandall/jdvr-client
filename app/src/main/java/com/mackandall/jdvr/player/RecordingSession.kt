package com.mackandall.jdvr.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.mackandall.jdvr.PlayRecording
import com.mackandall.jdvr.data.Buffer
import com.mackandall.jdvr.data.ItemDetails
import com.mackandall.jdvr.data.ItemPlayback
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Segment
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A recording in the player: playing it and reporting where it's got to, its commercial and intro
 * marks, the recordings either side (Previous / Next), and what the info box shows.
 */
@Stable
internal class RecordingSession(
    val recording: PlayRecording,
    private val player: ExoPlayer,
) {
    private val itemId get() = recording.itemId
    var playback by mutableStateOf<ItemPlayback?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set

    // Still recording: its marks are Comskip's live ones, refreshed.
    var inProgress by mutableStateOf(false)
        private set

    // A live TV buffer (JellyDVR Live): ● keeps it as a recording.
    var buffer by mutableStateOf<Buffer?>(null)
    var previous by mutableStateOf<PlayRecording?>(null)
        private set
    var next by mutableStateOf<PlayRecording?>(null)
        private set

    // What's playing (the top box and the Info panel), and the next episode's name.
    var details by mutableStateOf<ItemDetails?>(null)
        private set
    var nextTitle by mutableStateOf<String?>(null)
        private set
    var commercialsLabel by mutableStateOf<String?>(null)
        private set
    private var opened: ItemPlayback? = null

    /** Plays it from where it was left (or the start asked for) and reports progress every 10 s. */
    fun start(): Job =
        MainScope().launch {
            try {
                val p = Jdvr.backend.startItem(itemId)
                opened = p
                playback = p
                val start = recording.startMs ?: p.resumeMs
                player.setMediaItem(mediaItemOf(p), start.coerceAtLeast(0))
                player.prepare()
                Jdvr.backend.reportItem(p, start, paused = recording.paused, event = "start")
                while (true) {
                    delay(10_000)
                    Jdvr.backend.reportItem(p, player.currentPosition, paused = !player.isPlaying, event = "progress")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Couldn't play %s", itemId)
                error = "Couldn't play this recording: ${e.message}"
            }
        }

    /** Where it stopped, reported in the background, so leaving the player never waits on the server. */
    fun reportStopped(positionMs: Long) {
        opened?.let { p -> MainScope().launch { attempt { Jdvr.backend.reportItem(p, positionMs, paused = true, event = "stop") } } }
    }

    /** Segments: Jellyfin's once a recording is finished; Comskip's live ones (refreshed) while it records. */
    suspend fun followSegments() {
        buffer = Jdvr.backend.bufferOfItem(itemId)
        while (true) {
            val (recordingNow, liveSegs) = Jdvr.backend.liveSegments(itemId)
            inProgress = recordingNow
            segments = if (recordingNow) liveSegs else Jdvr.backend.segments(itemId)
            if (!recordingNow) break
            delay(15_000)
        }
    }

    suspend fun reloadSegments() {
        attempt { segments = Jdvr.backend.segments(itemId) }
    }

    suspend fun loadDetails() {
        details = attempt { Jdvr.backend.details(itemId) }
        commercialsLabel = attempt { Jdvr.backend.commercialBadges(listOf(itemId)) }?.values?.firstOrNull()?.label
    }

    /**
     * Previous / next: the channel's other buffers (from a live TV buffer), or the show's other
     * recordings in episode order; and "Next · S3:E2 Mobile Suit Toyman" for a show.
     */
    suspend fun loadNeighbours() {
        val live = recording.liveChannelId
        val ids =
            if (live != null) {
                Jdvr.backend.buffers(live).mapNotNull { it.itemId?.toUuid() }
            } else {
                showOrder(playback?.seriesId ?: return)
            }
        val i = ids.indexOf(itemId)
        if (i < 0) return
        previous = ids.getOrNull(i - 1)?.let { PlayRecording(it, startMs = if (live != null) 0L else null, liveChannelId = live) }
        next = ids.getOrNull(i + 1)?.let { PlayRecording(it, startMs = if (live != null) 0L else null, liveChannelId = live) }
        nextTitle =
            next?.takeIf { live == null }?.let { n ->
                attempt { Jdvr.backend.libItem(n.itemId) }?.let { listOfNotNull(it.seasonEpisode, it.name).joinToString(" ") }
            }
    }

    // The show's recordings in episode order (else by date); for a library or archive episode, not
    // among them, the show's own episodes.
    private suspend fun showOrder(series: String): List<java.util.UUID> {
        val all = attempt { Jdvr.backend.showRecordings(series, Jdvr.prefs.recordingsFilter) }.orEmpty()
        val ordered =
            if (all.all { it.season != null && it.episode != null }) {
                all.sortedWith(compareBy({ it.season }, { it.episode }))
            } else {
                all.sortedBy { it.recordedUtc ?: "" }
            }
        val ids = ordered.mapNotNull { it.id.toUuid() }
        if (itemId in ids) return ids
        return series
            .toUuid()
            ?.let { attempt { Jdvr.backend.episodes(it, null) } }
            ?.map { it.id }
            .orEmpty()
    }
}

/** The recording, with the subtitle files Jellyfin keeps beside the video as extra tracks. */
private fun mediaItemOf(p: ItemPlayback): MediaItem =
    MediaItem
        .Builder()
        .setUri(p.url)
        .setSubtitleConfigurations(
            p.subtitles.map { st ->
                MediaItem.SubtitleConfiguration
                    .Builder(android.net.Uri.parse(st.url))
                    .setMimeType(st.mimeType)
                    .setLanguage(st.language)
                    .setLabel(st.label)
                    .build()
            },
        ).build()
