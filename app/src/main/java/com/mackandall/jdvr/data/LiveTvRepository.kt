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
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
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
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
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

    /** A recording (library item): how to play it, and where the user left off. */
    data class ItemPlayback(
        val itemId: UUID,
        val name: String,
        val url: String,
        val mediaSourceId: String?,
        val playSessionId: String?,
        val playMethod: PlayMethod,
        val resumeMs: Long,
        val durationMs: Long?,
    )

    suspend fun startItem(itemId: UUID): ItemPlayback =
        io {
            val item = api.userLibraryApi.getItem(itemId, Jdvr.userId).content
            val response =
                api.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = Jdvr.userId,
                            deviceProfile = Jdvr.deviceProfile,
                            maxStreamingBitrate = 120_000_000,
                            enableDirectPlay = true,
                            enableDirectStream = true,
                            enableTranscoding = true,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = true,
                        ),
                    ).content
            response.errorCode?.let { error("Jellyfin can't play this: $it") }
            val source = response.mediaSources.firstOrNull() ?: error("No stream for this recording")
            val url =
                if (source.supportsDirectPlay || source.transcodingUrl == null) {
                    api.videosApi.getVideoStreamUrl(itemId = itemId, mediaSourceId = source.id, static = true, tag = source.eTag, playSessionId = response.playSessionId)
                } else {
                    api.createUrl(source.transcodingUrl!!)
                }
            ItemPlayback(
                itemId = itemId,
                name = listOfNotNull(item.seriesName, item.name).distinct().joinToString(" - "),
                url = url,
                mediaSourceId = source.id,
                playSessionId = response.playSessionId,
                playMethod = if (source.supportsDirectPlay) PlayMethod.DIRECT_PLAY else if (source.supportsDirectStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE,
                resumeMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000L,
                durationMs = item.runTimeTicks?.let { it / 10_000L },
            )
        }

    suspend fun reportItem(
        p: ItemPlayback,
        positionMs: Long,
        paused: Boolean,
        event: String,
    ) = io {
        val ticks = positionMs * 10_000L
        when (event) {
            "start" ->
                api.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = true, itemId = p.itemId, mediaSourceId = p.mediaSourceId, isPaused = paused, isMuted = false,
                        positionTicks = ticks, playMethod = p.playMethod, repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT, playSessionId = p.playSessionId,
                    ),
                )
            "progress" ->
                api.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        canSeek = true, itemId = p.itemId, mediaSourceId = p.mediaSourceId, isPaused = paused, isMuted = false,
                        positionTicks = ticks, playMethod = p.playMethod, repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT, playSessionId = p.playSessionId,
                    ),
                )
            else ->
                api.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = p.itemId, mediaSourceId = p.mediaSourceId, positionTicks = ticks, failed = false, playSessionId = p.playSessionId),
                )
        }
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
        body: String? = null,
    ): T? =
        try {
            val r = withContext(Dispatchers.IO) { api.request(method = method, pathTemplate = path, queryParameters = query, requestBody = body) }
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
    // ---- Recorded Shows (Episode History), recordings folder only ------------------------------

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
    )

    @Serializable
    private data class RecordingCards(
        @SerialName("Cards") val cards: List<RecordingCard> = emptyList(),
    )

    /** What the DVR recorded, newest first, from the recordings folders ("current"). */
    suspend fun recordedShows(): List<RecordingCard>? =
        plugin<RecordingCards>(HttpMethod.GET, "/EpisodeHistory/Recordings", mapOf("filter" to "current", "limit" to 200))?.cards

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
    )

    @Serializable
    private data class EpisodeRecordings(
        @SerialName("Recordings") val recordings: List<EpisodeRecording> = emptyList(),
    )

    /** A show's recordings, newest first (archived ones left out: recordings folder only for now). */
    suspend fun showRecordings(seriesId: String): List<EpisodeRecording>? =
        plugin<EpisodeRecordings>(HttpMethod.GET, "/EpisodeHistory/Recordings/Series/$seriesId")?.recordings?.filterNot { it.archived }

    fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String? = itemId.toUuid()?.let { imageUrl(it, maxHeight) }

    // ---- Series recordings and Recording Favourites ------------------------------------------

    suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> =
        io { api.liveTvApi.getSeriesTimers().content.items }

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

    suspend fun favourites(): FavouritesList? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Favourites")

    suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message? = plugin(HttpMethod.POST, "/JellyDvrSchedule/Favourites/$ruleId", body = "{\"Enabled\":$enabled}")

    suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message? =
        plugin(
            HttpMethod.POST,
            "/JellyDvrSchedule/Favourites",
            body = json.encodeToString(mapOf("Title" to title, "Match" to if (keyword) "keyword" else "title", "Type" to "any")),
        )

    // ---- Scheduled recordings (JellyDVR Schedule's fast list) ---------------------------------

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

    suspend fun upcoming(): List<Upcoming>? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Upcoming")

    suspend fun tuners(channelId: UUID): TunerStatus? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Tuners", mapOf("channelId" to channelId.toString()))
}

/** Jellyfin ids come without dashes; UUID.fromString needs them. */
fun String.toUuid(): UUID? = toUUIDOrNull()
