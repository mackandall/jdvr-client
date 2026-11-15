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

    /** A recording that produced nothing: the most serious kind. */
    val severe get() = kind == "failed"
}

/** JellyDVR Schedule's /JellyDvrSchedule/Alerts. */
class AlertsPlugin(
    private val server: PluginClient,
) : ServerAlerts {
    override suspend fun alerts(): AlertsList? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Alerts")

    override suspend fun dismissAlert(id: String): Boolean = server.bytes(HttpMethod.POST, "/JellyDvrSchedule/Alerts/$id/Dismiss") != null
}
