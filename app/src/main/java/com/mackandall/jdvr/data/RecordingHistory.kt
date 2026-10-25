package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jellyfin.sdk.api.client.HttpMethod
import java.util.UUID

/**
 * What the server remembers about recordings: Recorded Shows and the list by date, watched marks
 * on guide programmes, Other airings and Re-record, the trash, and Home's attention list. On
 * Jellyfin this is the Episode History plugin; without it these answer null.
 */
interface RecordingHistory {
    /** Episode History's guide mark symbols (to show its marks as coloured chips). */
    suspend fun markSymbols(): MarkSymbols?

    suspend fun hasEpisodeHistory(): Boolean

    /** "Other airings" of a library item or a guide entry (null without Episode History). */
    suspend fun airings(
        itemId: UUID? = null,
        programId: UUID? = null,
    ): AiringsResult?

    suspend fun rerecordState(itemId: UUID): RerecordState?

    /** Re-records a flawed recording at [programId], or the best airing found when null. */
    suspend fun rerecord(
        itemId: UUID,
        programId: String?,
        reason: String?,
    ): Message?

    suspend fun cancelRerecord(itemId: UUID): Boolean

    /** Episode History's trash: deleted recordings that can still be put back. Null without it. */
    suspend fun trash(): TrashList?

    /** Puts a deleted recording back; false when it couldn't (something is already where it was). */
    suspend fun restoreFromTrash(id: String): Boolean

    suspend fun deleteFromTrash(id: String): Boolean

    suspend fun emptyTrash(): Int?

    /** Days a delete stays in the trash, or null when deletes are for good (no trash). Asked again after 2 minutes. */
    suspend fun trashDays(): Int?

    /**
     * Episode History: the recordings being made right now behind an item (the item itself, or
     * for a show any of its episodes). Null on a server without that endpoint.
     */
    suspend fun activeRecordings(itemId: UUID): ActiveRecordings?

    /** Stops the recording writing this item's file; what was recorded so far is kept. */
    suspend fun stopRecording(recordingId: String): Boolean

    /**
     * A show's recordings in the recordings folders (archived copies stay): what deleting them
     * all would remove ([dryRun]), or delete them. With [archive] it's the archived copies
     * instead, and the recordings stay. Episode History only; null without it.
     */
    suspend fun deleteShowRecordings(
        seriesId: UUID,
        dryRun: Boolean,
        archive: Boolean = false,
    ): ShowDelete?

    /** What Episode History knows about a guide programme: marked watched, a copy to play, forgettable. */
    suspend fun ehProgram(programId: UUID): EhProgram?

    suspend fun setProgramWatched(
        programId: UUID,
        watched: Boolean,
    ): kotlinx.serialization.json.JsonObject?

    /** "Forget this episode": the history no longer counts it as recorded or watched (admins). */
    suspend fun forgetProgram(programId: UUID): kotlinx.serialization.json.JsonObject?

    /**
     * "Forget this show": Episode History forgets every episode of the show [itemId] belongs to (a
     * show, an episode or a guide entry), so a series recording records them again ([dryRun]
     * counts). Administrators; null otherwise or without the plugin.
     */
    suspend fun forgetShow(
        itemId: UUID,
        dryRun: Boolean,
    ): ShowForget?

    suspend fun forgetRecording(itemId: UUID): Forgot?

    /** Episode History's recordings: "current" (live recordings folder), "archived", or "all". */
    suspend fun recordedShows(filter: String = "current"): List<RecordingCard>?

    /** Every recording, newest first, one each with its channel. Null on an older Episode History. */
    suspend fun recordingsByDate(filter: String = "current"): DateRecordings?

    /** A show's recordings, newest first (archived ones left out: recordings folder only for now). */
    suspend fun showRecordings(
        seriesId: String,
        filter: String = "current",
    ): List<EpisodeRecording>?

    /** Episode History's part of Home: what needs attention, and the recordings drive. Null without it. */
    suspend fun homeExtras(): HomeExtras?
}

@Serializable
data class MarkSymbols(
    @SerialName("Watched") val watched: String = "",
    @SerialName("InLibrary") val inLibrary: String = "",
    @SerialName("RecordedBefore") val recordedBefore: String = "",
    @SerialName("Skipped") val skipped: String = "",
    @SerialName("OtherAiring") val otherAiring: String = "",
)

/** One airing of a movie or episode: where, when, and how it fits the tuners. */
@Serializable
data class Airing(
    @SerialName("ProgramId") val programId: String,
    @SerialName("Name") val name: String = "",
    @SerialName("EpisodeTitle") val episodeTitle: String? = null,
    @SerialName("Start") val start: String = "",
    @SerialName("End") val end: String = "",
    @SerialName("ChannelId") val channelId: String? = null,
    @SerialName("ChannelName") val channelName: String? = null,
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("HasLogo") val hasLogo: Boolean = false,
    @SerialName("CommercialFree") val commercialFree: Boolean = false,
    // "recording" or "series" when a recording is set for it already.
    @SerialName("Recording") val recording: String? = null,
    @SerialName("Busy") val busy: Int = 0,
    @SerialName("Tuners") val tuners: Int = 0,
    @SerialName("Clash") val clash: Boolean = false,
    @SerialName("Clashes") val clashes: List<String> = emptyList(),
    @SerialName("IsThis") val isThis: Boolean = false,
    @SerialName("OnNow") val onNow: Boolean = false,
)

@Serializable
data class AiringsResult(
    @SerialName("Title") val title: String? = null,
    @SerialName("IsMovie") val isMovie: Boolean = false,
    @SerialName("Tuners") val tuners: Int = 0,
    @SerialName("Airings") val airings: List<Airing> = emptyList(),
    // How far the guide reaches, and the series' first and last episodes in it.
    @SerialName("GuideUntil") val guideUntil: String? = null,
    @SerialName("GuideShowings") val guideShowings: Int = 0,
    @SerialName("GuideFirst") val guideFirst: GuideEpisode? = null,
    @SerialName("GuideLast") val guideLast: GuideEpisode? = null,
)

@Serializable
data class GuideEpisode(
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Start") val start: String = "",
) {
    /** "S1 E29", or its title when it has no numbers. */
    val label: String? get() = episode?.let { e -> listOfNotNull(season?.let { "S$it" }, "E$e").joinToString(" ") } ?: title
}

/** Whether "Re-record…" is offered for an item, and where an asked-for re-record stands. */
@Serializable
data class RerecordState(
    @SerialName("Eligible") val eligible: Boolean = false,
    @SerialName("Why") val why: String? = null,
    @SerialName("CanManage") val canManage: Boolean = false,
    // waiting, scheduled, replaced, gaveup, kept…
    @SerialName("State") val state: String? = null,
    @SerialName("Asked") val asked: Boolean = false,
    @SerialName("Reason") val reason: String? = null,
    @SerialName("NextStart") val nextStart: String? = null,
    @SerialName("NextChannel") val nextChannel: String? = null,
    @SerialName("Short") val short: String? = null,
    @SerialName("Text") val text: String? = null,
) {
    val pending: Boolean get() = asked && (state == "waiting" || state == "scheduled")
}

@Serializable
data class DeletedRecording(
    @SerialName("Partial") val partial: Boolean = false,
    @SerialName("Deleted") val deleted: Int = 0,
    // Moved to Episode History's trash (and kept that many days) rather than deleted.
    @SerialName("Trashed") val trashed: Boolean = false,
    @SerialName("TrashDays") val trashDays: Int? = null,
)

@Serializable
data class TrashItem(
    @SerialName("Id") val id: String,
    @SerialName("Title") val title: String = "",
    @SerialName("Show") val show: String? = null,
    // "S6E8 · Frozen Evidence", a movie's year, or "27 episodes".
    @SerialName("Detail") val detail: String? = null,
    @SerialName("Kind") val kind: String = "Video",
    @SerialName("Videos") val videos: Int = 1,
    @SerialName("Bytes") val bytes: Long = 0,
    @SerialName("DeletedUtc") val deletedUtc: String = "",
    @SerialName("ExpiresUtc") val expiresUtc: String = "",
    @SerialName("DeletedBy") val deletedBy: String? = null,
    @SerialName("Place") val place: String? = null,
)

@Serializable
data class TrashList(
    @SerialName("Enabled") val enabled: Boolean = false,
    @SerialName("KeepDays") val keepDays: Int = 0,
    @SerialName("MinFreeGb") val minFreeGb: Int = 0,
    // Put things back (anyone who may delete); delete them for good (administrators).
    @SerialName("CanRestore") val canRestore: Boolean = false,
    @SerialName("CanEmpty") val canEmpty: Boolean = false,
    @SerialName("Bytes") val bytes: Long = 0,
    @SerialName("Items") val items: List<TrashItem> = emptyList(),
)

@Serializable
private data class TrashRestored(
    @SerialName("Restored") val restored: Boolean = false,
)

@Serializable
private data class TrashDeleted(
    @SerialName("Deleted") val deleted: Int = 0,
)

@Serializable
data class ActiveRecording(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    // The Live TV channel it is recorded from (Episode History since 2026-10-14).
    @SerialName("ChannelId") val channelId: String? = null,
)

@Serializable
data class ActiveRecordings(
    // Whether this user may stop them (Live TV management).
    @SerialName("CanStop") val canStop: Boolean = false,
    @SerialName("Recordings") val recordings: List<ActiveRecording> = emptyList(),
)

@Serializable
private data class StoppedRecording(
    @SerialName("Stopped") val stopped: Boolean = false,
)

@Serializable
data class ShowDelete(
    @SerialName("Count") val count: Int = 0,
    @SerialName("Bytes") val bytes: Long = 0,
    @SerialName("Skipped") val skipped: Int = 0,
    @SerialName("Deleted") val deleted: Int = 0,
    // The shares the files are on, for the confirmation.
    @SerialName("Places") val places: List<String> = emptyList(),
    @SerialName("Trashed") val trashed: Boolean = false,
)

@Serializable
data class EhProgram(
    @SerialName("MarkedWatched") val markedWatched: Boolean = false,
    @SerialName("CanForget") val canForget: Boolean = false,
    @SerialName("LibraryItemId") val libraryItemId: String? = null,
    @SerialName("LibraryItemPositionTicks") val libraryItemPositionTicks: Long = 0L,
)

@Serializable
data class Forgot(
    @SerialName("Forgotten") val forgotten: Int = 0,
    @SerialName("KeptInLibrary") val keptInLibrary: Int = 0,
)

/**
 * "Forget this episode" from a recording: deletes it (a copy in the library would count it as
 * recorded again) and what the history remembers, so it records again (admins).
 */
@Serializable
data class ShowForget(
    @SerialName("Show") val show: String? = null,
    @SerialName("Rows") val rows: Int = 0,
    @SerialName("Episodes") val episodes: Int = 0,
    @SerialName("Forgotten") val forgotten: Int = 0,
    @SerialName("KeptInLibrary") val keptInLibrary: Int = 0,
)

@Serializable
data class RecordingCard(
    @SerialName("Type") val type: String = "",
    @SerialName("Id") val id: String,
    @SerialName("ImageId") val imageId: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Subtitle") val subtitle: String? = null,
    @SerialName("Count") val count: Int = 1,
    @SerialName("UnplayedCount") val unplayedCount: Int = 0,
    @SerialName("PartialCount") val partialCount: Int = 0,
    @SerialName("PartialMinutes") val partialMinutes: Int? = null,
    @SerialName("RecordedUtc") val recordedUtc: String? = null,
    // How many of them are still being recorded.
    @SerialName("RecordingCount") val recordingCount: Int = 0,
)

@Serializable
private data class RecordingCards(
    @SerialName("Cards") val cards: List<RecordingCard> = emptyList(),
)

@Serializable
data class DateChannel(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Number") val number: String? = null,
    @SerialName("HasLogo") val hasLogo: Boolean = false,
)

/** One recording in the list by date. */
@Serializable
data class DateRecording(
    @SerialName("Id") val id: String,
    @SerialName("Type") val type: String = "",
    @SerialName("ImageId") val imageId: String? = null,
    @SerialName("Title") val title: String = "",
    @SerialName("EpisodeTitle") val episodeTitle: String? = null,
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
    @SerialName("Year") val year: Int? = null,
    @SerialName("RecordedUtc") val recordedUtc: String? = null,
    @SerialName("Minutes") val minutes: Int? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercent") val playedPercent: Int = 0,
    @SerialName("Archived") val archived: Boolean = false,
    @SerialName("Recording") val recording: Boolean = false,
    @SerialName("Partial") val partial: Boolean = false,
    // The repair's chip ("Stalled · 7 min lost", "Waiting for a re-airing") and its story.
    @SerialName("StatusShort") val statusShort: String? = null,
    @SerialName("StatusText") val statusText: String? = null,
    @SerialName("Overview") val overview: String? = null,
    // Unknown for recordings made before Episode History noted channels, or not by the DVR.
    @SerialName("Channel") val channel: DateChannel? = null,
)

@Serializable
data class DateRecordings(
    // Where the channel logo goes: "left" (start of the line) or "right" (end), a plugin setting.
    @SerialName("DateLogo") val dateLogo: String = "left",
    @SerialName("Recordings") val recordings: List<DateRecording> = emptyList(),
)

@Serializable
data class EpisodeRecording(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Season") val season: Int? = null,
    @SerialName("Episode") val episode: Int? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("Archived") val archived: Boolean = false,
    @SerialName("RecordedUtc") val recordedUtc: String? = null,
    @SerialName("Partial") val partial: Boolean = false,
    @SerialName("Minutes") val minutes: Int? = null,
    // Still being recorded.
    @SerialName("Recording") val recording: Boolean = false,
)

@Serializable
private data class EpisodeRecordings(
    @SerialName("Recordings") val recordings: List<EpisodeRecording> = emptyList(),
)

@Serializable
data class Attention(
    // "failed", "incomplete" or "stalled".
    @SerialName("Kind") val kind: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Detail") val detail: String = "",
    @SerialName("ItemId") val itemId: String? = null,
)

@Serializable
data class Drive(
    @SerialName("FreeBytes") val freeBytes: Long = 0,
    @SerialName("TotalBytes") val totalBytes: Long = 0,
)

@Serializable
data class HomeExtras(
    @SerialName("Attention") val attention: List<Attention> = emptyList(),
    @SerialName("Drive") val drive: Drive? = null,
)

/** Episode History's endpoints (/EpisodeHistory/…). */
class EpisodeHistoryPlugin(
    private val server: PluginClient,
) : RecordingHistory {
    override suspend fun markSymbols(): MarkSymbols? = server.call(HttpMethod.GET, "/EpisodeHistory/Symbols")

    // Whether the Episode History plugin answers on this server: a yes is reused for 5 minutes (so
    // a plugin removed meanwhile stops being offered soon after); a no is asked again next time.
    private var episodeHistoryAt = 0L

    override suspend fun hasEpisodeHistory(): Boolean {
        if (System.currentTimeMillis() - episodeHistoryAt < 5 * 60_000) return true
        val yes = markSymbols() != null
        episodeHistoryAt = if (yes) System.currentTimeMillis() else 0L
        return yes
    }

    override suspend fun airings(
        itemId: UUID?,
        programId: UUID?,
    ): AiringsResult? =
        server.call(
            HttpMethod.GET,
            "/EpisodeHistory/Airings",
            listOfNotNull(itemId?.let { "itemId" to it.toString() }, programId?.let { "programId" to it.toString() }).toMap(),
        )

    override suspend fun rerecordState(itemId: UUID): RerecordState? = server.call(HttpMethod.GET, "/EpisodeHistory/Item/$itemId/Rerecord")

    override suspend fun rerecord(
        itemId: UUID,
        programId: String?,
        reason: String?,
    ): Message? =
        server.call(
            HttpMethod.POST,
            "/EpisodeHistory/Item/$itemId/Rerecord",
            listOfNotNull(programId?.let { "programId" to it }, reason?.let { "reason" to it }).toMap(),
        )

    override suspend fun cancelRerecord(itemId: UUID): Boolean = server.bytes(HttpMethod.DELETE, "/EpisodeHistory/Item/$itemId/Rerecord") != null

    override suspend fun trash(): TrashList? = server.call<TrashList>(HttpMethod.GET, "/EpisodeHistory/Trash")?.also { t -> trashDaysCache = (if (t.enabled) t.keepDays else null) to System.currentTimeMillis() }

    override suspend fun restoreFromTrash(id: String): Boolean = server.call<TrashRestored>(HttpMethod.POST, "/EpisodeHistory/Trash/$id/Restore")?.restored == true

    override suspend fun deleteFromTrash(id: String): Boolean = (server.call<TrashDeleted>(HttpMethod.DELETE, "/EpisodeHistory/Trash/$id")?.deleted ?: 0) > 0

    override suspend fun emptyTrash(): Int? = server.call<TrashDeleted>(HttpMethod.DELETE, "/EpisodeHistory/Trash")?.deleted

    private var trashDaysCache: Pair<Int?, Long>? = null

    override suspend fun trashDays(): Int? {
        trashDaysCache?.let { (days, at) -> if (System.currentTimeMillis() - at < 120_000) return days }
        val t = trash()
        if (t == null) trashDaysCache = null to System.currentTimeMillis()
        return trashDaysCache?.first
    }

    override suspend fun activeRecordings(itemId: UUID): ActiveRecordings? = server.call(HttpMethod.GET, "/EpisodeHistory/Item/${itemId.plain()}/ActiveRecordings")

    override suspend fun stopRecording(recordingId: String): Boolean = server.call<StoppedRecording>(HttpMethod.POST, "/EpisodeHistory/Item/${recordingId.replace("-", "")}/StopRecording")?.stopped == true

    override suspend fun deleteShowRecordings(
        seriesId: UUID,
        dryRun: Boolean,
        archive: Boolean,
    ): ShowDelete? =
        server.call(
            HttpMethod.DELETE,
            "/EpisodeHistory/Recordings/Series/${seriesId.plain()}",
            buildMap {
                if (dryRun) put("dryRun", "true")
                if (archive) put("archive", "true")
            },
        )

    override suspend fun ehProgram(programId: UUID): EhProgram? = server.call(HttpMethod.GET, "/EpisodeHistory/Program/${programId.plain()}")

    override suspend fun setProgramWatched(
        programId: UUID,
        watched: Boolean,
    ): kotlinx.serialization.json.JsonObject? = server.call<kotlinx.serialization.json.JsonObject>(if (watched) HttpMethod.POST else HttpMethod.DELETE, "/EpisodeHistory/Program/${programId.plain()}/Watched")

    override suspend fun forgetProgram(programId: UUID): kotlinx.serialization.json.JsonObject? = server.call<kotlinx.serialization.json.JsonObject>(HttpMethod.DELETE, "/EpisodeHistory/Program/${programId.plain()}/History")

    override suspend fun forgetShow(
        itemId: UUID,
        dryRun: Boolean,
    ): ShowForget? = server.call(HttpMethod.DELETE, "/EpisodeHistory/Item/${itemId.plain()}/ShowHistory", if (dryRun) mapOf("dryRun" to "true") else emptyMap())

    override suspend fun forgetRecording(itemId: UUID): Forgot? = server.call(HttpMethod.DELETE, "/EpisodeHistory/Item/${itemId.plain()}/History", mapOf("deleteRecording" to true))

    override suspend fun recordedShows(filter: String): List<RecordingCard>? = server.call<RecordingCards>(HttpMethod.GET, "/EpisodeHistory/Recordings", mapOf("filter" to filter, "limit" to 200))?.cards

    override suspend fun recordingsByDate(filter: String): DateRecordings? = server.call(HttpMethod.GET, "/EpisodeHistory/Recordings/ByDate", mapOf("filter" to filter))

    override suspend fun showRecordings(
        seriesId: String,
        filter: String,
    ): List<EpisodeRecording>? =
        server.call<EpisodeRecordings>(HttpMethod.GET, "/EpisodeHistory/Recordings/Series/$seriesId")?.recordings?.filter {
            when (filter) {
                "archived" -> it.archived
                "all" -> true
                else -> !it.archived
            }
        }

    override suspend fun homeExtras(): HomeExtras? = server.call(HttpMethod.GET, "/EpisodeHistory/Home")
}
