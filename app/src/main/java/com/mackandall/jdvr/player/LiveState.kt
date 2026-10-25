package com.mackandall.jdvr.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Buffer
import com.mackandall.jdvr.data.Channel
import com.mackandall.jdvr.data.ItemDetails
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Program
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.attemptResult
import com.mackandall.jdvr.data.plainId
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** This visit's buffer: the one recording now (an older, finished one is the wrong place to land). */
internal fun List<Buffer>.currentBuffer() = lastOrNull { it.recording && it.itemId != null }

/** What's over the live picture: the panels, the info bars' timing, messages and typed digits. */
@Stable
internal class LiveOverlays(
    private val scope: CoroutineScope,
) {
    // The controls row (OK).
    var actionsOpen by mutableStateOf(false)

    // The mini guide over the bottom of the picture: ▼ opens it, Back closes it.
    var miniOpen by mutableStateOf(false)

    // The programme's full panel (Info): details plus Record, series, favourites, watched…
    var infoOpen by mutableStateOf(false)

    // The info bars show until then.
    var osdUntil by mutableLongStateOf(System.currentTimeMillis() + 6000)
    var lastKeyAt by mutableLongStateOf(System.currentTimeMillis())
    var note by mutableStateOf<String?>(null)

    // A channel number being typed; it goes after 2 s without another digit.
    var typed by mutableStateOf("")
    var typedAt by mutableLongStateOf(0L)

    val anyPanel get() = actionsOpen || miniOpen || infoOpen

    fun showOsd(forMs: Long = 5000) {
        osdUntil = System.currentTimeMillis() + forMs
    }

    /** A message over the picture for a few seconds. */
    fun flash(
        message: String,
        forMs: Long = 3000,
    ) {
        scope.launch {
            note = message
            delay(forMs)
            if (note == message) note = null
        }
    }
}

/** The channels, and what the player knows about the one playing: its buffer, recording and programme. */
@Stable
internal class LiveInfo {
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var buffers by mutableStateOf<List<Buffer>>(emptyList())
        private set

    // Guide programmes that are only a buffer (their timers look like recordings).
    var bufferIds by mutableStateOf<Set<String>>(emptySet())
        private set

    // The programme on now as a recording, to watch from the start.
    var fromStart by mutableStateOf<UUID?>(null)
        private set

    // The top info box's extras for the programme on now: ratings, genres, and what's next.
    var nowDetails by mutableStateOf<ItemDetails?>(null)
        private set
    var nextUp by mutableStateOf<Program?>(null)
        private set
    private var recordArmedUntil = 0L

    fun channel(id: UUID) = channels.firstOrNull { it.id == id }

    /** The programme on now really records (not just JellyDVR Live's buffer). */
    fun realRecording(channelId: UUID): Boolean =
        buffers.currentBuffer()?.kept == true || channel(channelId)?.now?.let { it.timerId != null && it.id.toString().plainId() !in bufferIds } == true

    suspend fun loadChannels() {
        attempt { channels = Jdvr.backend.channels() }
    }

    suspend fun loadBuffers(channelId: UUID) {
        buffers = Jdvr.backend.buffers(channelId)
        bufferIds = Jdvr.backend.bufferPrograms()
    }

    /** JellyDVR Live's buffer (from tune-in) and, if the programme is a real recording, the recording. */
    suspend fun loadRecording(channelId: UUID) {
        loadBuffers(channelId)
        fromStart = channel(channelId)?.now?.takeIf { it.isRecording }?.let { Jdvr.backend.recordingOfProgram(it.id) }
    }

    suspend fun loadNow(channelId: UUID) {
        nowDetails = null
        nextUp = null
        val now = channel(channelId)?.now ?: return
        nowDetails = attempt { Jdvr.backend.details(now.id) }
        nextUp = attempt { Jdvr.backend.programs(listOf(channelId), now.end, now.end.plusHours(3)) }?.firstOrNull { !it.start.isBefore(now.end) }
    }

    /**
     * ● Record the programme on now: keep the buffer (so it's recorded from tune-in), or schedule it
     * if there's no buffer yet. On a programme already recording, a second press stops it. Says what
     * happened; null when nothing is on.
     */
    suspend fun record(channelId: UUID): String? {
        val now = channel(channelId)?.now ?: return null
        val b = buffers.currentBuffer()
        val realTimer = now.timerId?.takeIf { now.id.toString().plainId() !in bufferIds }
        return attemptResult {
            when {
                b?.kept == true -> {
                    Jdvr.backend.keepBuffer(b.id, false)
                    "Won't record ${now.name} (back to a buffer)"
                }
                realTimer != null && System.currentTimeMillis() < recordArmedUntil -> {
                    recordArmedUntil = 0L
                    Jdvr.backend.cancelRecording(realTimer)
                    "Stopped recording ${now.name}"
                }
                realTimer != null -> {
                    recordArmedUntil = System.currentTimeMillis() + 4000
                    "Already recording ${now.name}: press ● again to stop it"
                }
                b != null -> {
                    Jdvr.backend.keepBuffer(b.id, true)
                    "Recording ${now.name}" + (b.startedUtc?.let { s -> runCatching { " from " + LocalDateTime.ofInstant(Instant.parse(s), ZoneId.systemDefault()).time() + " (when you tuned in)" }.getOrNull() } ?: "")
                }
                else -> {
                    Jdvr.backend.record(now.id, series = false)
                    "Recording ${now.name}"
                }
            }
        }.getOrElse { "Couldn't change the recording: ${it.message}" }
    }
}
