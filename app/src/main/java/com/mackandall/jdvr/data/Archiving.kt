package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * Moving recordings to archive locations, and what this TV may do. On Jellyfin this is JellyDVR
 * Space; without it these answer null.
 */
interface Archiving {
    /** This device's rights; null without JellyDVR Space (or one too old to have them). */
    suspend fun deviceRights(): DeviceRights?

    /** JellyDVR Space: recordings being moved to the archive right now, with progress; admins. */
    suspend fun moving(): List<MoveProgress>?

    /** JellyDVR Space: whether the item is a DVR recording, and the archive locations it can go to; admins. */
    suspend fun archiveChoices(itemId: UUID): ArchiveChoices?

    /** Move a recording to an archive location (in the background on the server); admins. */
    suspend fun archive(
        itemId: UUID,
        target: String,
    ): Message?

    /** JellyDVR Space: where the show of [itemId] (series, episode or guide programme) is stored, and the folders to choose from. */
    suspend fun showStorage(itemId: UUID): ShowStorage? = null

    /** Saves a show's storage folders in order (empty: it stays on the recording drive). */
    suspend fun setShowStorage(
        show: String,
        locations: List<String>,
    ): Message? = null

    /** Saves one programme's or recording's own folders: [mode] "show" (follow the show), "drive" or "folders". */
    suspend fun setRecordingStorage(
        itemId: UUID,
        mode: String,
        locations: List<String>,
    ): Message? = null
}

/**
 * What this TV may do, set per device on JellyDVR Space's settings page. These only narrow
 * what the signed-in user may do; the server refuses the rest whatever the app shows.
 */
@Serializable
data class DeviceRights(
    @SerialName("LiveTv") val liveTv: Boolean = true,
    @SerialName("Record") val record: Boolean = true,
    @SerialName("Delete") val delete: Boolean = true,
    @SerialName("DeleteArchive") val deleteArchive: Boolean = false,
    @SerialName("Archive") val archive: Boolean = true,
    @SerialName("Edit") val edit: Boolean = true,
)

@Serializable
data class ArchiveChoice(
    @SerialName("Path") val path: String = "",
    @SerialName("Kind") val kind: String = "both",
    @SerialName("FreeBytes") val freeBytes: Long? = null,
    @SerialName("TotalBytes") val totalBytes: Long? = null,
    @SerialName("KeepFreeGb") val keepFreeGb: Int = 0,
    @SerialName("Destination") val destination: String = "",
    @SerialName("Problem") val problem: String? = null,
    @SerialName("Note") val note: String? = null,
)

@Serializable
data class ArchiveChoices(
    @SerialName("Name") val name: String = "",
    @SerialName("IsRecording") val isRecording: Boolean = false,
    @SerialName("Kind") val kind: String = "",
    @SerialName("Bytes") val bytes: Long = 0,
    // A show: how many of its recordings move together, and which of them can't move now.
    @SerialName("Count") val count: Int = 1,
    @SerialName("Note") val note: String? = null,
    @SerialName("Blocked") val blocked: String? = null,
    @SerialName("InFlight") val inFlight: String? = null,
    @SerialName("Targets") val targets: List<ArchiveChoice> = emptyList(),
    @SerialName("Progress") val progress: MoveProgress? = null,
)

@Serializable
data class MoveProgress(
    @SerialName("ItemId") val itemId: String = "",
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("Destination") val destination: String = "",
    @SerialName("Bytes") val bytes: Long = 0,
    @SerialName("CopiedBytes") val copiedBytes: Long = 0,
    @SerialName("Started") val started: Boolean = false,
) {
    val fraction get() = if (bytes > 0) (copiedBytes.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

/** A storage folder set up in JellyDVR Space (on the web page), with its space. */
@Serializable
data class StorageFolder(
    @SerialName("Path") val path: String = "",
    @SerialName("Label") val label: String = "",
    @SerialName("Kind") val kind: String = "both",
    @SerialName("KeepFreeGb") val keepFreeGb: Int = 0,
    @SerialName("FreeBytes") val freeBytes: Long? = null,
    @SerialName("TotalBytes") val totalBytes: Long? = null,
    @SerialName("Problem") val problem: String? = null,
)

/** A show's storage folders ("Store this show in…"): finished recordings move to the first with room. */
@Serializable
data class ShowStorage(
    @SerialName("Show") val show: String = "",
    @SerialName("Locations") val locations: List<String> = emptyList(),
    @SerialName("Folders") val folders: List<StorageFolder> = emptyList(),
    // The folder the next finished recording would go to.
    @SerialName("MovesTo") val movesTo: String? = null,
    @SerialName("OnRecordingDrive") val onRecordingDrive: Boolean = false,
    @SerialName("SortMode") val sortMode: String = "preview",
    // Set for a movie: shows only.
    @SerialName("Problem") val problem: String? = null,
    // A guide programme's or a recording's own choice, which wins over the show's; null for a series.
    @SerialName("Recording") val recording: RecordingStorage? = null,
) {
    /** The chosen folders' short names, in order. */
    val labels get() = labelsOf(locations)

    fun labelsOf(paths: List<String>) = paths.map { p -> folders.firstOrNull { it.path == p }?.label ?: p }
}

/** One programme's or recording's own storage ("Store this recording in…"). */
@Serializable
data class RecordingStorage(
    @SerialName("Title") val title: String = "",
    // A guide programme: the choice waits for the recording.
    @SerialName("Upcoming") val upcoming: Boolean = false,
    // "show" (follows the show's setting), "drive" (stays on the recording drive) or "folders".
    @SerialName("Mode") val mode: String = "show",
    @SerialName("Locations") val locations: List<String> = emptyList(),
)

@Serializable
private data class RecordingStorageRequest(
    @SerialName("ItemId") val itemId: String,
    @SerialName("Mode") val mode: String,
    @SerialName("Locations") val locations: List<String>,
)

@Serializable
private data class ShowStorageRequest(
    @SerialName("Show") val show: String,
    @SerialName("Locations") val locations: List<String>,
)

/** JellyDVR Space's endpoints (/JellyDvrSpace/…). */
class SpacePlugin(
    private val server: PluginClient,
) : Archiving {
    override suspend fun deviceRights(): DeviceRights? = server.call(HttpMethod.GET, "/JellyDvrSpace/Rights")

    override suspend fun moving(): List<MoveProgress>? = server.call(HttpMethod.GET, "/JellyDvrSpace/Moving")

    override suspend fun archiveChoices(itemId: UUID): ArchiveChoices? = server.call(HttpMethod.GET, "/JellyDvrSpace/Archive/$itemId")

    override suspend fun archive(
        itemId: UUID,
        target: String,
    ): Message? = server.call(HttpMethod.POST, "/JellyDvrSpace/Archive/$itemId", mapOf("target" to target))

    override suspend fun showStorage(itemId: UUID): ShowStorage? =
        server.call<ShowStorage>(HttpMethod.GET, "/JellyDvrSpace/ShowStorage/$itemId")?.also { ShowStorageCache.put(itemId, it) }

    override suspend fun setShowStorage(
        show: String,
        locations: List<String>,
    ): Message? =
        server.call<Message>(
            HttpMethod.POST,
            "/JellyDvrSpace/ShowStorage",
            body = server.json.encodeToString(ShowStorageRequest.serializer(), ShowStorageRequest(show, locations)),
        ).also { ShowStorageCache.clear() }

    override suspend fun setRecordingStorage(
        itemId: UUID,
        mode: String,
        locations: List<String>,
    ): Message? =
        server.call<Message>(
            HttpMethod.POST,
            "/JellyDvrSpace/RecordingStorage",
            body = server.json.encodeToString(RecordingStorageRequest.serializer(), RecordingStorageRequest(itemId.toString(), mode, locations)),
        ).also { ShowStorageCache.clear() }
}

/**
 * Storage as last read, by programme or recording (one airing can have its own choice): the
 * guide's info band shows it without asking the server (which checks every folder's space,
 * network shares too) each time the cursor comes back. Any save clears it all.
 */
object ShowStorageCache {
    private const val FRESH_MS = 120_000L
    private val byItem = java.util.concurrent.ConcurrentHashMap<UUID, Pair<Long, ShowStorage>>()

    fun put(
        itemId: UUID,
        s: ShowStorage,
    ) {
        byItem[itemId] = System.currentTimeMillis() to s
    }

    /** Goes up on each save, so what shows storage reads it again. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun clear() {
        byItem.clear()
        version++
    }

    /** The item's storage if read in the last two minutes, else null. */
    fun fresh(itemId: UUID): ShowStorage? = byItem[itemId]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.second
}
