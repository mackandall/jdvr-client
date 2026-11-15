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
)

/**
 * The programme menu's question, kept with the others in [Answers]: resting on a programme in the
 * guide asks it ahead, so OK shows the whole menu at once (Episode History's answer alone takes
 * over a second). It also remembers the menu's shape (whether this server answers for Episode
 * History and Schedule), so their buttons hold their places while a first answer is on its way.
 */
internal object ProgramAsk {
    /** [allBuffers]: look for its buffer even when it isn't one yet (just after Watch live started one). */
    fun panel(
        p: Program,
        allBuffers: Boolean = false,
    ) = Ask("panel:${p.id}") { load(p, allBuffers) }

    // The menu's shape, for the server it was learnt from.
    private var shapeOf by mutableStateOf<String?>(null)
    private var eh by mutableStateOf(false)
    private var schedule by mutableStateOf(false)

    /** Whether this server's last answer had Episode History's / Schedule's part. */
    val ehAnswers: Boolean get() = eh && shapeOf == Jdvr.serverKey
    val scheduleAnswers: Boolean get() = schedule && shapeOf == Jdvr.serverKey

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
            shapeOf = Jdvr.serverKey
            this@ProgramAsk.eh = info.eh != null
            this@ProgramAsk.schedule = info.schedule != null
            info
        }
}
