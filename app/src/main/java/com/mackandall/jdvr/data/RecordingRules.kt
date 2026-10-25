package com.mackandall.jdvr.data

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Recording Favourites (title and keyword rules), what will record and why, Record instead, and
 * the tuners. On Jellyfin this is JellyDVR Schedule; without it these answer null.
 */
interface RecordingRules {
    /** JellyDVR Schedule: was this airing skipped / moved, and is the show a Recording Favourite. */
    suspend fun scheduleState(programId: UUID): ScheduleState?

    /** Which show a recording stands for, and the Recording Favourite covering it (if any). */
    suspend fun favouriteForItem(itemId: UUID): FavouriteFor?

    suspend fun recordInstead(programId: UUID): Message?

    suspend fun addFavourite(programId: UUID): Message?

    suspend fun removeFavourite(ruleId: String): Message?

    suspend fun favourites(): FavouritesList?

    suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message?

    suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message?

    suspend fun upcoming(): List<Upcoming>?

    /** JellyDVR Schedule: can this channel start, and what are the tuners busy with. */
    suspend fun tuners(channelId: UUID?): TunerStatus?
}

@Serializable
data class Outcome(
    @SerialName("Kind") val kind: String = "",
    @SerialName("Detail") val detail: String = "",
)

@Serializable
data class Favourite(
    @SerialName("Id") val id: String,
    @SerialName("Title") val title: String = "",
)

@Serializable
data class ScheduleState(
    @SerialName("Outcome") val outcome: Outcome? = null,
    @SerialName("Favourite") val favourite: Favourite? = null,
)

@Serializable
data class FavouriteRef(
    @SerialName("Id") val id: String = "",
    @SerialName("Title") val title: String = "",
)

@Serializable
data class FavouriteFor(
    @SerialName("Title") val title: String = "",
    @SerialName("IsMovie") val isMovie: Boolean = false,
    @SerialName("Favourite") val favourite: FavouriteRef? = null,
)

@Serializable
data class BusyInput(
    @SerialName("ChannelId") val channelId: String,
    @SerialName("ChannelName") val channelName: String = "",
    @SerialName("Number") val number: String = "",
    @SerialName("Recording") val recording: List<String> = emptyList(),
    @SerialName("Watching") val watching: List<String> = emptyList(),
    @SerialName("EndsUtc") val endsUtc: String? = null,
)

@Serializable
data class TunerStatus(
    @SerialName("Capacity") val capacity: Int = 0,
    @SerialName("Full") val full: Boolean = false,
    @SerialName("IsChannel") val isChannel: Boolean = false,
    @SerialName("RequestedChannel") val requestedChannel: String? = null,
    @SerialName("Busy") val busy: List<BusyInput> = emptyList(),
)

@Serializable
data class NextRecording(
    @SerialName("Title") val title: String = "",
    @SerialName("Channel") val channel: String = "",
    @SerialName("Start") val start: String = "",
)

@Serializable
data class FavouriteEntry(
    @SerialName("Id") val id: String,
    @SerialName("Title") val title: String = "",
    @SerialName("Match") val match: String = "title",
    @SerialName("Channels") val channels: String = "",
    @SerialName("NewOnly") val newOnly: Boolean = false,
    @SerialName("Type") val type: String = "series",
    @SerialName("Enabled") val enabled: Boolean = true,
    @SerialName("Upcoming") val upcoming: Int = 0,
    @SerialName("Next") val next: List<NextRecording> = emptyList(),
)

@Serializable
data class FavouritesList(
    @SerialName("CanManage") val canManage: Boolean = false,
    @SerialName("Favourites") val favourites: List<FavouriteEntry> = emptyList(),
)

@Serializable
data class Upcoming(
    @SerialName("TimerId") val timerId: String = "",
    @SerialName("ProgramId") val programId: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("EpisodeTitle") val episodeTitle: String? = null,
    @SerialName("SeasonEpisode") val seasonEpisode: String? = null,
    @SerialName("IsRepeat") val isRepeat: Boolean = false,
    @SerialName("Start") val start: String = "",
    @SerialName("End") val end: String = "",
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("ChannelName") val channelName: String? = null,
    @SerialName("Kind") val kind: String = "",
    @SerialName("Favourite") val favourite: String? = null,
    @SerialName("RecordingNow") val recordingNow: Boolean = false,
)

/** JellyDVR Schedule's endpoints (/JellyDvrSchedule/…). */
class SchedulePlugin(
    private val server: PluginClient,
) : RecordingRules {
    override suspend fun scheduleState(programId: UUID): ScheduleState? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Program/$programId")

    override suspend fun favouriteForItem(itemId: UUID): FavouriteFor? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Favourites/ForItem/$itemId")

    override suspend fun recordInstead(programId: UUID): Message? = server.call(HttpMethod.POST, "/JellyDvrSchedule/RecordInstead/$programId")

    override suspend fun addFavourite(programId: UUID): Message? = server.call(HttpMethod.POST, "/JellyDvrSchedule/Favourites/FromItem/$programId")

    override suspend fun removeFavourite(ruleId: String): Message? = server.call(HttpMethod.DELETE, "/JellyDvrSchedule/Favourites/$ruleId")

    override suspend fun favourites(): FavouritesList? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Favourites")

    override suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message? = server.call(HttpMethod.POST, "/JellyDvrSchedule/Favourites/$ruleId", body = "{\"Enabled\":$enabled}")

    override suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message? =
        server.call(
            HttpMethod.POST,
            "/JellyDvrSchedule/Favourites",
            body = server.json.encodeToString(mapOf("Title" to title, "Match" to if (keyword) "keyword" else "title", "Type" to "any")),
        )

    override suspend fun upcoming(): List<Upcoming>? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Upcoming")

    override suspend fun tuners(channelId: UUID?): TunerStatus? = server.call(HttpMethod.GET, "/JellyDvrSchedule/Tuners", if (channelId == null) emptyMap() else mapOf("channelId" to channelId.toString()))
}
