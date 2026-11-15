package com.mackandall.jdvr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Buffer
import com.mackandall.jdvr.data.EhProgram
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Program
import com.mackandall.jdvr.data.ScheduleState
import com.mackandall.jdvr.data.ShowForget
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.plainId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/** Everything a programme's menu asks the server before it can show all its buttons. */
internal class PanelInfo(
    val schedule: ScheduleState?,
    val eh: EhProgram?,
    val showForget: ShowForget?,
    val isBuffer: Boolean,
    val buffer: Buffer?,
    // Being recorded now: the recording, for Watch from start.
    val recordingItem: UUID?,
    val at: Long = System.currentTimeMillis(),
)

/**
 * The programme menu's answers, fetched ahead: resting on a programme in the guide fetches what its
 * menu needs, so OK shows the whole menu at once (Episode History's answer alone takes over a
 * second). Kept a few minutes; the menu still asks again when it opens and updates if anything
 * changed. It also remembers the menu's shape (whether this server answers for Episode History
 * and Schedule), so their buttons hold their places while a first answer is on its way.
 */
internal object PanelCache {
    private const val FRESH_MS = 3 * 60_000L
    private const val KEEP = 80

    // Main thread only (the scope runs there; the calls themselves switch to I/O).
    private val done =
        object : LinkedHashMap<UUID, PanelInfo>(KEEP, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, PanelInfo>?) = size > KEEP
        }
    private val pending = HashMap<UUID, Deferred<PanelInfo>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: String? = null

    /** Whether the last answer had Episode History's / Schedule's part. */
    var ehAnswers by mutableStateOf(false)
        private set
    var scheduleAnswers by mutableStateOf(false)
        private set

    // Another server: nothing from the last one applies.
    private fun checkServer() {
        val now = Jdvr.serverKey
        if (now != server) {
            server = now
            done.clear()
            pending.clear()
            ehAnswers = false
            scheduleAnswers = false
        }
    }

    /** What's known about a programme's menu, if it's recent. */
    fun cached(id: UUID): PanelInfo? {
        checkServer()
        return done[id]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
    }

    /**
     * The menu's answers: the fetch already under way for it (a prefetch), else a new one. [fresh]:
     * always a new one (after an action changed things); [allBuffers]: look for its buffer even when
     * it isn't one yet (just after Watch live started one).
     */
    fun get(
        p: Program,
        allBuffers: Boolean = false,
        fresh: Boolean = false,
    ): Deferred<PanelInfo> {
        checkServer()
        if (!fresh) pending[p.id]?.let { return it }
        val d = scope.async { load(p, allBuffers) }
        pending[p.id] = d
        d.invokeOnCompletion { if (pending[p.id] === d) pending.remove(p.id) }
        return d
    }

    /** Fetch ahead (focus resting on a programme), unless it's known or already on its way. */
    fun prefetch(p: Program) {
        if (cached(p.id) == null && pending[p.id] == null) get(p)
    }

    // All asked at once: one after another, the menu took seconds to fill.
    private suspend fun load(
        q: Program,
        allBuffers: Boolean,
    ): PanelInfo =
        coroutineScope {
            val schedule = async { attempt { Jdvr.backend.scheduleState(q.id) } }
            val eh = async { attempt { Jdvr.backend.ehProgram(q.id) } }
            val forget = async { if (q.isSeries) attempt { Jdvr.backend.forgetShow(q.id, dryRun = true) } else null }
            val recording = async { if (q.isRecording && q.isOn()) attempt { Jdvr.backend.recordingOfProgram(q.id) } else null }
            val bufferPart =
                async {
                    val isBuffer = q.id.toString().plainId() in attempt { Jdvr.backend.bufferPrograms() }.orEmpty()
                    val buffer = if (isBuffer || allBuffers) attempt { Jdvr.backend.buffers(q.channelId) }?.lastOrNull { it.programId?.plainId() == q.id.toString().plainId() } else null
                    isBuffer to buffer
                }
            val (isBuffer, buffer) = bufferPart.await()
            val info = PanelInfo(schedule.await(), eh.await(), forget.await(), isBuffer, buffer, recording.await())
            done[q.id] = info
            ehAnswers = info.eh != null
            scheduleAnswers = info.schedule != null
            info
        }
}
