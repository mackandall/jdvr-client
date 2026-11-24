package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Problems the server found: recordings that failed or came out short, a stuck tuner, a full
 * drive, a guide that stopped updating (JellyDVR Schedule's alerts, fed by Episode History too).
 * Without the plugin (or on another server type) these answer null and nothing is shown.
 */
interface ServerAlerts {
    /** Open problems and those closed lately, with the TV settings; null when there are no alerts. */
    suspend fun alerts(): AlertsList? = null

    /** Takes a problem off the open list; false when it failed. */
    suspend fun dismissAlert(id: String): Boolean = false

    /** Dismisses every open problem; how many, or null when it failed. */
    suspend fun dismissAllAlerts(): Int? = null

    /**
     * "Don't record again": Episode History stops looking for (and takes off the schedule) another
     * airing of a short or failed recording, and the problem is dismissed. False when it couldn't.
     */
    suspend fun dontRecordAgain(a: ServerAlert): Boolean = false
}

@Serializable
data class AlertsList(
    @SerialName("Open") val open: List<ServerAlert> = emptyList(),
    @SerialName("Closed") val closed: List<ServerAlert> = emptyList(),
    @SerialName("Tv") val tv: AlertsTv = AlertsTv(),
) {
    /** The open problems this TV shows (the server's TV column). */
    val shownOpen get() = open.filter { it.onTv }
}

@Serializable
data class AlertsTv(
    @SerialName("PopupOnOpen") val popupOnOpen: Boolean = true,
    @SerialName("ProblemsTile") val problemsTile: Boolean = true,
    @SerialName("PopupDuringPlayback") val popupDuringPlayback: Boolean = false,
)

@Serializable
data class ServerAlert(
    @SerialName("Id") val id: String = "",
    // failed, short, tuner, space, guide
    @SerialName("Kind") val kind: String = "",
    // What reported it knows it by: Episode History's are "rec:" + the airing (JellyDVR Schedule ≥ 1.2.3).
    @SerialName("Key") val key: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Text") val text: String = "",
    @SerialName("Next") val next: String? = null,
    @SerialName("Where") val where: String? = null,
    @SerialName("AiredUtc") val airedUtc: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("ProgramId") val programId: String? = null,
    // open, fixed, dismissed, ended
    @SerialName("State") val state: String = "open",
    @SerialName("CreatedUtc") val createdUtc: String = "",
    @SerialName("ClosedUtc") val closedUtc: String? = null,
    @SerialName("ClosedText") val closedText: String? = null,
    @SerialName("Repeats") val repeats: Int = 0,
    @SerialName("OnTv") val onTv: Boolean = true,
) {
    /** The short label shown beside it. */
    val label
        get() =
            when (kind) {
                "failed" -> "FAILED"
                "short" -> "SHORT"
                "tuner" -> "TUNER"
                "space" -> "SPACE"
                "guide" -> "GUIDE"
                else -> kind.uppercase()
            }

    /** A short or failed recording Episode History would record again, that "Don't record again" can stop. */
    val rerecordable get() = (kind == "failed" || kind == "short") && (itemId != null || key.startsWith("rec:"))

    /** A recording that produced nothing: the most serious kind. */
    val severe get() = kind == "failed"
}

/** JellyDVR Schedule's /JellyDvrSchedule/Alerts. */
class AlertsPlugin(
    private val server: PluginClient,
) : ServerAlerts {
    override suspend fun alerts(): AlertsList? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Alerts")

    override suspend fun dismissAlert(id: String): Boolean = server.bytes(HttpMethod.POST, "/JellyDvrSchedule/Alerts/$id/Dismiss") != null

    override suspend fun dismissAllAlerts(): Int? = server.call<DismissedCount>(HttpMethod.POST, "/JellyDvrSchedule/Alerts/DismissAll")?.dismissed

    override suspend fun dontRecordAgain(a: ServerAlert): Boolean {
        // By the recording when it's still there, else by the airing (a recording that never made a file).
        val kept =
            (a.itemId != null && server.bytes(HttpMethod.POST, "/EpisodeHistory/Item/${a.itemId}/KeepRecording") != null) ||
                (a.key.startsWith("rec:") && server.bytes(HttpMethod.POST, "/EpisodeHistory/Failed/Keep", mapOf("key" to a.key.removePrefix("rec:"))) != null)
        return kept && dismissAlert(a.id)
    }
}

@Serializable
data class DismissedCount(
    @SerialName("Dismissed") val dismissed: Int = 0,
)
