package com.mackandall.jdvr.data

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Commercial breaks and recording edits: badges, scans, the live scan, the program start and
 * cuts. On Jellyfin this is the Comskip Commercial Segments plugin; without it these answer null.
 */
interface Commercials {
    /** Comskip's badge per item (keys: ids without dashes); items never scanned are left out. */
    suspend fun commercialBadges(ids: List<UUID>): Map<String, CommercialBadge>

    /**
     * The same for show cards whose ids aren't items: a summary of their recordings. Jellyfin's
     * shows are items, so [commercialBadges] covers them.
     */
    suspend fun showCommercialBadges(showIds: List<String>): Map<String, CommercialBadge> = emptyMap()

    /** Scan (again) for commercials; admins. */
    suspend fun scanCommercials(itemId: UUID): Message?

    /** Where Comskip is with an item: scanning now, found breaks, none, failed. */
    suspend fun commercialStatus(itemId: UUID): CommercialStatus?

    /**
     * Removes the commercials Comskip found (a scan that shouldn't have happened): only the
     * commercial marks go, and automatic detection leaves the item alone afterwards.
     */
    suspend fun removeCommercials(itemId: UUID): Cleared?

    /** Trim the stray 2.0 / 5.1 audio at the start and end of a recording; admins. */
    suspend fun fixAudioFormat(itemId: UUID): Message?

    /**
     * Comskip plugin: "Starts here (this recording)" at [seconds]; editors. [keyframe]: [seconds] is
     * a keyframe picked from [keyframes], and a cut starts exactly there.
     */
    suspend fun setProgramStart(itemId: UUID, seconds: Double, keyframe: Boolean = false): ProgramStartResult?

    /** The keyframes either side of [seconds]: a cut can only start on one (Comskip plugin; editors). */
    suspend fun keyframes(itemId: UUID, seconds: Double): Keyframes?

    /** The picture at [seconds] of a recording, to choose a keyframe by. */
    suspend fun frame(itemId: UUID, seconds: Double, width: Int = 400): androidx.compose.ui.graphics.ImageBitmap?

    /** "Remember card (all recordings)": the frame at [seconds] is the channel's card before its programs; editors. */
    suspend fun rememberStartCard(itemId: UUID, seconds: Double): ProgramStartResult?

    /** Comskip plugin: whether this user may edit recordings (administrators, and users the plugin allows), and what a trim keeps. */
    suspend fun editing(): Editing?

    /** The recording's program start (a skip marker), and whether a trim's original was kept; editors. */
    suspend fun programStartStatus(itemId: UUID): ProgramStartStatus?

    /** Remove the program start's skip marker; editors. */
    suspend fun clearProgramStart(itemId: UUID): ProgramStartResult?

    /** Put a trimmed recording's kept original back; editors. */
    suspend fun undoTrim(itemId: UUID): ProgramStartResult?

    /** Look for a remembered card in the recording's opening minutes; editors. */
    suspend fun findProgramStart(itemId: UUID): ProgramStartResult?

    /** Comskip plugin: recordings being cut right now (program start, audio format), with progress; everyone. */
    suspend fun cuts(): List<CutJob>?

    /** Stop a cut while the original is still untouched; editors. */
    suspend fun stopCut(itemId: UUID): Message?

    /**
     * Commercials found so far in a recording still being made (Comskip's live scan); Jellyfin has
     * none for it until it finishes. First: still in progress.
     */
    suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>>

    /** Programmes being recorded now whose recording Comskip is scanning live (the guide's CS tag), with minutes found so far. */
    suspend fun guideScanning(): Map<String, Int?>

    /** The recording of a programme being recorded now, as a library item (Comskip plugin). */
    suspend fun recordingOfProgram(programId: UUID): UUID?
}

@Serializable
data class CommercialBadge(
    @SerialName("State") val state: String = "",
    @SerialName("Minutes") val minutes: Int? = null,
    @SerialName("Done") val done: Int? = null,
    @SerialName("Total") val total: Int? = null,
    @SerialName("Failed") val failed: Boolean? = null,
) {
    /** Short text for a badge: "4m ads", "scanning", "no ads", "scan failed", "8/10 scanned". */
    val label: String
        get() =
            when (state) {
                "found" -> "${minutes ?: 0}m ads"
                "scanning" -> "scanning" + (minutes?.let { " · ${it}m" } ?: "")
                "none" -> "no ads"
                "failed" -> "scan failed"
                "summary" -> "${done ?: 0}/${total ?: 0} scanned" + if (failed == true) " !" else ""
                else -> state
            }
}

@Serializable
private data class CommercialBadges(
    @SerialName("Items") val items: Map<String, CommercialBadge> = emptyMap(),
)

@Serializable
data class CommercialStatus(
    @SerialName("InFlight") val inFlight: Boolean = false,
    @SerialName("Status") val status: String? = null,
    @SerialName("BreakCount") val breakCount: Int? = null,
) {
    /** Comskip found breaks: the button offers to remove them instead of scanning. */
    val found get() = status == "Detected" && (breakCount ?: 0) > 0
}

@Serializable
data class Cleared(
    @SerialName("Cleared") val cleared: Boolean = false,
    @SerialName("Message") val message: String = "",
)

@Serializable
data class ProgramStartResult(
    @SerialName("Ok") val ok: Boolean = false,
    @SerialName("Trimmed") val trimmed: Boolean = false,
    @SerialName("StartSeconds") val startSeconds: Double? = null,
    @SerialName("Message") val message: String = "",
)

@Serializable
data class Keyframes(
    @SerialName("Seconds") val seconds: Double = 0.0,
    @SerialName("Before") val before: Double? = null,
    @SerialName("After") val after: Double? = null,
)

@Serializable
data class ProgramStartStatus(
    @SerialName("StartSeconds") val startSeconds: Double? = null,
    @SerialName("Action") val action: String? = null,
    @SerialName("CanUndo") val canUndo: Boolean = false,
    // When the kept original's undo time is up (null: kept for good).
    @SerialName("UndoUntil") val undoUntil: String? = null,
)

@Serializable
data class Editing(
    @SerialName("CanEdit") val canEdit: Boolean = false,
    @SerialName("ProgramStartTrims") val programStartTrims: Boolean = false,
    @SerialName("KeepOriginal") val keepOriginal: Boolean = false,
    @SerialName("UndoDays") val undoDays: Int = 0,
) {
    /** How a trim can be taken back, for the confirmations. */
    val undoNote: String
        get() = when {
            keepOriginal -> "Undo trim puts it back."
            undoDays > 0 -> "Undo trim can put it back for $undoDays day${if (undoDays == 1) "" else "s"}."
            else -> "This can't be undone."
        }
}

@Serializable
data class CutJob(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Kind") val kind: String = "",
    @SerialName("Phase") val phase: String = "checking",
    @SerialName("Stoppable") val stoppable: Boolean = false,
    @SerialName("Bytes") val bytes: Long = 0,
    @SerialName("DoneBytes") val doneBytes: Long = 0,
) {
    val fraction get() = if (bytes > 0) (doneBytes.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

@Serializable
private data class LiveSegment(
    @SerialName("Type") val type: String = "Commercial",
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0,
)

@Serializable
private data class LiveSegments(
    @SerialName("InProgress") val inProgress: Boolean = false,
    @SerialName("Items") val items: List<LiveSegment> = emptyList(),
)

@Serializable
private data class GuideBadge(
    @SerialName("ProgramId") val programId: String = "",
    @SerialName("RecordingItemId") val recordingItemId: String? = null,
    @SerialName("State") val state: String = "",
    @SerialName("Minutes") val minutes: Int? = null,
)

@Serializable
private data class GuideBadges(
    @SerialName("Programs") val programs: List<GuideBadge> = emptyList(),
)

/** Comskip Commercial Segments' endpoints (/ComskipSegments/…). */
class ComskipPlugin(
    private val server: PluginClient,
) : Commercials {
    override suspend fun commercialBadges(ids: List<UUID>): Map<String, CommercialBadge> =
        ids.chunked(250).flatMap { chunk ->
            server.call<CommercialBadges>(HttpMethod.GET, "/ComskipSegments/Badges", mapOf("ids" to chunk.joinToString(",") { it.plain() }))?.items.orEmpty().entries
        }.associate { it.key.plainId() to it.value }

    override suspend fun scanCommercials(itemId: UUID): Message? = server.call(HttpMethod.POST, "/ComskipSegments/Detect/$itemId", mapOf("force" to true))

    override suspend fun commercialStatus(itemId: UUID): CommercialStatus? = server.call(HttpMethod.GET, "/ComskipSegments/Status/$itemId")

    override suspend fun removeCommercials(itemId: UUID): Cleared? = server.call(HttpMethod.POST, "/ComskipSegments/Clear/$itemId", mapOf("keep" to true))

    override suspend fun fixAudioFormat(itemId: UUID): Message? = server.call(HttpMethod.POST, "/ComskipSegments/AudioTrim/Apply/$itemId")

    override suspend fun setProgramStart(itemId: UUID, seconds: Double, keyframe: Boolean): ProgramStartResult? =
        server.call(HttpMethod.POST, "/ComskipSegments/ProgramStart/$itemId", mapOf("seconds" to seconds, "keyframe" to keyframe))

    override suspend fun keyframes(itemId: UUID, seconds: Double): Keyframes? =
        server.call(HttpMethod.GET, "/ComskipSegments/ProgramStart/$itemId/Keyframes", mapOf("seconds" to seconds))

    override suspend fun frame(itemId: UUID, seconds: Double, width: Int): ImageBitmap? =
        server.bytes(HttpMethod.GET, "/ComskipSegments/ProgramStart/$itemId/Frame", mapOf("seconds" to seconds, "width" to width))
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

    override suspend fun rememberStartCard(itemId: UUID, seconds: Double): ProgramStartResult? =
        server.call(HttpMethod.POST, "/ComskipSegments/ProgramStart/$itemId/Card", mapOf("seconds" to seconds))

    override suspend fun editing(): Editing? = server.call(HttpMethod.GET, "/ComskipSegments/Editing")

    override suspend fun programStartStatus(itemId: UUID): ProgramStartStatus? = server.call(HttpMethod.GET, "/ComskipSegments/ProgramStart/$itemId")

    override suspend fun clearProgramStart(itemId: UUID): ProgramStartResult? = server.call(HttpMethod.DELETE, "/ComskipSegments/ProgramStart/$itemId")

    override suspend fun undoTrim(itemId: UUID): ProgramStartResult? = server.call(HttpMethod.POST, "/ComskipSegments/ProgramStart/$itemId/Undo")

    override suspend fun findProgramStart(itemId: UUID): ProgramStartResult? =
        server.call(HttpMethod.POST, "/ComskipSegments/ProgramStart/$itemId/Find")

    override suspend fun cuts(): List<CutJob>? = server.call(HttpMethod.GET, "/ComskipSegments/Cuts")

    override suspend fun stopCut(itemId: UUID): Message? = server.call(HttpMethod.POST, "/ComskipSegments/Cuts/$itemId/Stop")

    override suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>> {
        val r = server.call<LiveSegments>(HttpMethod.GET, "/ComskipSegments/Live/${itemId.plain()}") ?: return false to emptyList()
        return r.inProgress to r.items.map { Segment(it.type, it.startTicks / 10_000L, it.endTicks / 10_000L) }
    }

    override suspend fun guideScanning(): Map<String, Int?> =
        server.call<GuideBadges>(HttpMethod.GET, "/ComskipSegments/Badges/Guide")?.programs.orEmpty().filter { it.state == "scanning" }.associate { it.programId.plainId() to it.minutes }

    override suspend fun recordingOfProgram(programId: UUID): UUID? {
        val key = programId.plain()
        return server.call<GuideBadges>(HttpMethod.GET, "/ComskipSegments/Badges/Guide")
            ?.programs
            ?.firstOrNull { it.programId.replace("-", "").equals(key, ignoreCase = true) }
            ?.recordingItemId
            ?.toUuid()
    }
}
