package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** A live channel, with what's on now. */
data class Channel(
    val id: UUID,
    val number: String,
    val name: String,
    val logoUrl: String?,
    val now: Program?,
)

/** A guide entry. [rawEpisodeTitle] still carries Episode History's marks ("💾 Ranch Dressing"). */
data class Program(
    val id: UUID,
    val channelId: UUID,
    val name: String,
    val rawEpisodeTitle: String?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val overview: String?,
    val isMovie: Boolean,
    val isSeries: Boolean,
    val isRepeat: Boolean,
    val season: Int?,
    val episode: Int?,
    val timerId: String?,
    val seriesTimerId: String?,
) {
    val isRecording: Boolean get() = timerId != null
    val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else null

    fun isOn(at: LocalDateTime = LocalDateTime.now()) = !start.isAfter(at) && end.isAfter(at)
}

/** Everything needed to play a live channel and to tell Jellyfin about it. */
data class LivePlayback(
    val channelId: UUID,
    val url: String,
    val mediaSourceId: String?,
    val liveStreamId: String?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
)

class LiveTvRepository {
    private val json = Json { ignoreUnknownKeys = true }

    // The Jellyfin SDK reads replies on the calling thread: never the main one (Android forbids it).
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private val api: ApiClient get() = Jdvr.api ?: error("Not signed in")

    // ---- channels and guide --------------------------------------------------------------

    suspend fun channels(): List<Channel> =
        io {
        val items =
            api.liveTvApi
                .getLiveTvChannels(
                    GetLiveTvChannelsRequest(
                        userId = Jdvr.userId,
                        addCurrentProgram = true,
                        enableImages = true,
                        imageTypeLimit = 1,
                        enableImageTypes = listOf(ImageType.PRIMARY),
                        enableUserData = false,
                    ),
                ).content.items
        items
            .map { dto ->
                Channel(
                    id = dto.id,
                    number = dto.number ?: dto.channelNumber ?: "",
                    name = dto.name ?: "",
                    logoUrl = if (dto.imageTags?.containsKey(ImageType.PRIMARY) == true) imageUrl(dto.id, 120) else null,
                    now = dto.currentProgram?.let { toProgram(it) },
                )
            }.sortedWith(compareBy({ it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name }))
    }

    suspend fun programs(
        channelIds: List<UUID>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Program> =
        io {
            api.liveTvApi
            .getPrograms(
                GetProgramsDto(
                    channelIds = channelIds,
                    userId = Jdvr.userId,
                    minEndDate = from,
                    maxStartDate = to,
                    sortBy = listOf(ItemSortBy.START_DATE),
                    fields = listOf(ItemFields.OVERVIEW),
                    enableImages = false,
                ),
            ).content.items
            .mapNotNull { toProgram(it) }
        }

    suspend fun program(id: UUID): Program? = io { api.liveTvApi.getProgram(id.toString(), Jdvr.userId).content.let { toProgram(it) } }

    fun imageUrl(
        itemId: UUID,
        maxHeight: Int,
    ): String = api.imageApi.getItemImageUrl(itemId, ImageType.PRIMARY, maxHeight = maxHeight)

    private fun toProgram(dto: BaseItemDto): Program? {
        val start = dto.startDate ?: return null
        val end = dto.endDate ?: return null
        return Program(
            id = dto.id,
            channelId = dto.channelId ?: return null,
            name = dto.name ?: "",
            rawEpisodeTitle = dto.episodeTitle,
            start = start,
            end = end,
            overview = dto.overview,
            isMovie = dto.isMovie == true,
            isSeries = dto.isSeries == true,
            isRepeat = dto.isRepeat == true,
            season = dto.parentIndexNumber,
            episode = dto.indexNumber,
            timerId = dto.timerId,
            seriesTimerId = dto.seriesTimerId,
        )
    }

    // ---- recording -------------------------------------------------------------------------

    suspend fun record(
        programId: UUID,
        series: Boolean,
    ) {
        val d = api.liveTvApi.getDefaultTimer(programId.toString()).content
        if (series) {
            api.liveTvApi.createSeriesTimer(d)
        } else {
            api.liveTvApi.createTimer(
                TimerInfoDto(
                    id = d.id,
                    type = d.type,
                    serverId = d.serverId,
                    externalId = d.externalId,
                    channelId = d.channelId,
                    externalChannelId = d.externalChannelId,
                    channelName = d.channelName,
                    programId = d.programId,
                    externalProgramId = d.externalProgramId,
                    name = d.name,
                    overview = d.overview,
                    startDate = d.startDate,
                    endDate = d.endDate,
                    serviceName = d.serviceName,
                    priority = d.priority,
                    prePaddingSeconds = d.prePaddingSeconds,
                    postPaddingSeconds = d.postPaddingSeconds,
                    isPrePaddingRequired = d.isPrePaddingRequired,
                    isPostPaddingRequired = d.isPostPaddingRequired,
                    keepUntil = d.keepUntil,
                ),
            )
        }
    }

    suspend fun cancelRecording(timerId: String) =
        io {
        api.liveTvApi.cancelTimer(timerId)
    }

    suspend fun cancelSeries(seriesTimerId: String) =
        io {
        api.liveTvApi.cancelSeriesTimer(seriesTimerId)
    }

    // ---- playback --------------------------------------------------------------------------

    /** Opens the channel's live stream and works out how to play it. */
    suspend fun startLive(channelId: UUID): LivePlayback =
        io {
        val response =
            api.mediaInfoApi
                .getPostedPlaybackInfo(
                    channelId,
                    PlaybackInfoDto(
                        userId = Jdvr.userId,
                        deviceProfile = Jdvr.deviceProfile,
                        maxStreamingBitrate = 120_000_000,
                        enableDirectPlay = true,
                        enableDirectStream = true,
                        enableTranscoding = true,
                        allowVideoStreamCopy = true,
                        allowAudioStreamCopy = true,
                        autoOpenLiveStream = true,
                    ),
                ).content
        response.errorCode?.let { error("Jellyfin can't play this channel: $it") }
        val source = response.mediaSources.firstOrNull() ?: error("No stream for this channel")
        val url =
            when {
                source.supportsDirectPlay || source.supportsDirectStream && source.transcodingUrl == null ->
                    api.videosApi.getVideoStreamUrl(
                        itemId = channelId,
                        mediaSourceId = source.id,
                        static = true,
                        tag = source.eTag,
                        playSessionId = response.playSessionId,
                        liveStreamId = source.liveStreamId,
                    )
                source.transcodingUrl != null -> api.createUrl(source.transcodingUrl!!)
                else -> error("No way to play this channel")
            }
        val method =
            when {
                source.supportsDirectPlay -> PlayMethod.DIRECT_PLAY
                source.supportsDirectStream -> PlayMethod.DIRECT_STREAM
                else -> PlayMethod.TRANSCODE
            }
        Timber.i("Live %s: %s via %s", channelId, method, url.substringBefore('?'))
        LivePlayback(channelId, url, source.id, source.liveStreamId, response.playSessionId, method)
    }

    suspend fun reportStart(p: LivePlayback) =
        io {
        api.playStateApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = false,
                itemId = p.channelId,
                mediaSourceId = p.mediaSourceId,
                isPaused = false,
                isMuted = false,
                playMethod = p.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
                playSessionId = p.playSessionId,
                liveStreamId = p.liveStreamId,
            ),
        )
    }

    suspend fun reportProgress(
        p: LivePlayback,
        paused: Boolean,
    ) {
        api.playStateApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = false,
                itemId = p.channelId,
                mediaSourceId = p.mediaSourceId,
                isPaused = paused,
                isMuted = false,
                playMethod = p.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
                playSessionId = p.playSessionId,
                liveStreamId = p.liveStreamId,
            ),
        )
    }

    /** Must carry the live stream id, or Jellyfin keeps the tuner open (found with Moonfin). */
    suspend fun reportStopped(p: LivePlayback) =
        io {
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = p.channelId,
                mediaSourceId = p.mediaSourceId,
                failed = false,
                playSessionId = p.playSessionId,
                liveStreamId = p.liveStreamId,
            ),
        )
    }

    // ---- our plugins (every call degrades quietly when a plugin is missing) ------------------

    private suspend inline fun <reified T> plugin(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): T? =
        try {
            val r = withContext(Dispatchers.IO) { api.request(method = method, pathTemplate = path, queryParameters = query) }
            json.decodeFromString<T>(r.body.decodeToString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Plugin call %s failed", path)
            null
        }

    @Serializable
    data class MarkSymbols(
        @SerialName("Watched") val watched: String = "",
        @SerialName("InLibrary") val inLibrary: String = "",
        @SerialName("RecordedBefore") val recordedBefore: String = "",
        @SerialName("Skipped") val skipped: String = "",
        @SerialName("OtherAiring") val otherAiring: String = "",
    )

    /** Episode History's guide mark symbols (to show its marks as coloured chips). */
    suspend fun markSymbols(): MarkSymbols? = plugin(HttpMethod.GET, "/EpisodeHistory/Symbols")

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

    /** JellyDVR Schedule: was this airing skipped / moved, and is the show a Recording Favourite. */
    suspend fun scheduleState(programId: UUID): ScheduleState? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Program/$programId")

    @Serializable
    data class Message(
        @SerialName("Message") val message: String = "",
    )

    suspend fun recordInstead(programId: UUID): Message? = plugin(HttpMethod.POST, "/JellyDvrSchedule/RecordInstead/$programId")

    suspend fun addFavourite(programId: UUID): Message? = plugin(HttpMethod.POST, "/JellyDvrSchedule/Favourites/FromItem/$programId")

    suspend fun removeFavourite(ruleId: String): Message? = plugin(HttpMethod.DELETE, "/JellyDvrSchedule/Favourites/$ruleId")

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

    /** JellyDVR Schedule: can this channel start, and what are the tuners busy with. */
    suspend fun tuners(channelId: UUID): TunerStatus? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Tuners", mapOf("channelId" to channelId.toString()))
}
