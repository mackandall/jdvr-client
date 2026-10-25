package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod
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
}
