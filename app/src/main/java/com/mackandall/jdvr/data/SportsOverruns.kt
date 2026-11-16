package com.mackandall.jdvr.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Sports overruns (JellyDVR Schedule): games running late, the recordings given extra time for
 * them, and the shows a late game pushes back. Without the plugin these answer null and the
 * guide shows nothing extra.
 */
interface SportsOverruns {
    /** What the guide marks; null without the plugin. */
    suspend fun overrunMarks(): OverrunMarksData? = null

    /** "Keep the normal end" (true) or "Follow the game again" (false); null when it worked, else why not. */
    suspend fun keepNormalEnd(
        timerId: String,
        keep: Boolean,
    ): String? = "Not available on this server"

    /** "Add 15 more minutes"; null when it worked, else why not. */
    suspend fun addFifteen(timerId: String): String? = "Not available on this server"
}

@Serializable
data class OverrunMarksData(
    // "affected", "all" or "off"
    @SerialName("Marks") val marks: String = "off",
    @SerialName("ShowScores") val showScores: Boolean = false,
    @SerialName("ShowEstimates") val showEstimates: Boolean = true,
    @SerialName("Buttons") val buttons: Boolean = false,
    @SerialName("Preview") val preview: Boolean = false,
    @SerialName("Games") val games: List<OverrunGame> = emptyList(),
    @SerialName("Recordings") val recordings: List<OverrunRecording> = emptyList(),
    @SerialName("Shifted") val shifted: List<OverrunShift> = emptyList(),
) {
    fun game(programId: String) = games.firstOrNull { it.programId == programId }

    fun recording(programId: String) = recordings.firstOrNull { it.programId == programId }

    fun shift(programId: String) = shifted.firstOrNull { it.programId == programId }
}

@Serializable
data class OverrunGame(
    @SerialName("ProgramId") val programId: String = "",
    @SerialName("ChannelId") val channelId: String = "",
    @SerialName("ListedStartUtc") val listedStartUtc: String = "",
    @SerialName("ListedEndUtc") val listedEndUtc: String = "",
    @SerialName("EstimatedEndUtc") val estimatedEndUtc: String? = null,
    @SerialName("Late") val late: Boolean = false,
    @SerialName("Final") val final: Boolean = false,
    @SerialName("Clock") val clock: String = "",
    @SerialName("Score") val score: String = "",
) {
    /** "LIVE · 4th 2:10 · PIT 24 – CIN 21", or "Final". */
    val label get() = (if (final) "Final" else "LIVE · $clock") + (if (score.isNotEmpty()) " · $score" else "")

    val listedEnd get() = overrunTime(listedEndUtc)

    val estimatedEnd get() = estimatedEndUtc?.let(::overrunTime)
}

@Serializable
data class OverrunRecording(
    @SerialName("ProgramId") val programId: String = "",
    @SerialName("TimerId") val timerId: String = "",
    @SerialName("ChannelId") val channelId: String = "",
    // "after", "game", "followup"
    @SerialName("Role") val role: String = "",
    @SerialName("ExtraMinutes") val extraMinutes: Int = 0,
    @SerialName("EstimateMinutes") val estimateMinutes: Int = 0,
    @SerialName("UserExtraMinutes") val userExtraMinutes: Int = 0,
    @SerialName("Kept") val kept: Boolean = false,
    @SerialName("Decided") val decided: Boolean = false,
    @SerialName("Recording") val recording: Boolean = false,
    @SerialName("EndUtc") val endUtc: String = "",
    @SerialName("GameTitle") val gameTitle: String = "",
    @SerialName("GameStatus") val gameStatus: String = "",
    @SerialName("Score") val score: String = "",
) {
    val total get() = extraMinutes + userExtraMinutes

    /** The short mark beside the title: "+22", "≈ +22" (an estimate), "Follow-up", "normal end"; null for none. */
    val tag: String?
        get() =
            when {
                role == "followup" -> "Follow-up"
                kept -> if (userExtraMinutes > 0) "+$userExtraMinutes" else "normal end"
                decided && total > 0 -> "+$total"
                estimateMinutes > 0 -> "≈ +${estimateMinutes + userExtraMinutes}"
                userExtraMinutes > 0 -> "+$userExtraMinutes"
                else -> null
            }

    /** An estimate, not decided yet (drawn dashed / outlined). */
    val isEstimate get() = !decided && !kept && estimateMinutes > 0

    /** The longer words for rows and the menu: "+22 min · game ran late". */
    val sentence: String?
        get() =
            when {
                role == "followup" -> "Follow-up · joined after"
                kept -> "Normal end kept" + (if (userExtraMinutes > 0) " · +$userExtraMinutes min by hand" else "")
                decided && total > 0 -> "+$total min · " + (if (role == "game") "for overtime" else "game ran late")
                estimateMinutes > 0 -> "≈ +${estimateMinutes + userExtraMinutes} min · game running late"
                userExtraMinutes > 0 -> "+$userExtraMinutes min by hand"
                else -> null
            }

    /** When it records until now (listed end + extra). */
    val newEnd get() = overrunTime(endUtc)?.plusMinutes(total.toLong())
}

@Serializable
data class OverrunShift(
    @SerialName("ProgramId") val programId: String = "",
    @SerialName("ShiftMinutes") val shiftMinutes: Int = 0,
)

/** The server's UTC times ("…Z"; a time without a zone is UTC too) in this TV's time. */
fun overrunTime(iso: String): LocalDateTime? =
    runCatching {
        val s = if (iso.endsWith("Z") || iso.contains('+') || iso.lastIndexOf('-') > 9) iso else iso + "Z"
        LocalDateTime.ofInstant(Instant.parse(s), ZoneId.systemDefault())
    }.getOrNull()

/** JellyDVR Schedule's /JellyDvrSchedule/Overruns. */
class OverrunsPlugin(
    private val server: PluginClient,
) : SportsOverruns {
    override suspend fun overrunMarks(): OverrunMarksData? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Overruns/Guide")

    override suspend fun keepNormalEnd(
        timerId: String,
        keep: Boolean,
    ): String? =
        if (server.bytes(if (keep) HttpMethod.POST else HttpMethod.DELETE, "/JellyDvrSchedule/Overruns/Timer/$timerId/Keep") != null) {
            null
        } else {
            "Couldn't change it: it may already be recording."
        }

    override suspend fun addFifteen(timerId: String): String? =
        if (server.bytes(HttpMethod.POST, "/JellyDvrSchedule/Overruns/Timer/$timerId/More") != null) {
            null
        } else {
            "Couldn't change it: it may already be recording."
        }
}

/** The latest marks, shared by the guide, the menu and the recording lists (refreshed by the guide every minute). */
object OverrunMarks {
    var latest by mutableStateOf<OverrunMarksData?>(null)
        private set

    private var fetchedAt = 0L

    /** Fetches again when [force] or older than [maxAgeMs]. */
    suspend fun refresh(
        force: Boolean = false,
        maxAgeMs: Long = 55_000,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - fetchedAt < maxAgeMs) return
        fetchedAt = now
        latest = runCatching { Jdvr.backend.overrunMarks() }.getOrNull()?.takeIf { it.marks != "off" }
    }
}
