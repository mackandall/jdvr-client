package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
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
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.launch
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
    // The same live stream as HLS (a remux the server keeps from tune-in on): where pause and
    // rewind go, so they're instant. Null if the server wouldn't give one.
    val hlsUrl: String? = null,
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
        val hls = if (method == PlayMethod.TRANSCODE) null else runCatching { hlsOf(channelId, source.id, source.liveStreamId, response.playSessionId) }.onFailure { Timber.w(it, "No HLS for %s", channelId) }.getOrNull()
        Timber.i("Live %s: %s via %s; HLS %s", channelId, method, url.substringBefore('?'), if (hls != null) "ready" else "none")
        LivePlayback(channelId, url, source.id, source.liveStreamId, response.playSessionId, method, hls ?: url.takeIf { method == PlayMethod.TRANSCODE })
    }

    /**
     * The open live stream again, as HLS (video and audio copied). It carries the direct play's
     * session id, so its progress reports keep the remux alive and its stop report ends it.
     */
    private suspend fun hlsOf(
        channelId: UUID,
        mediaSourceId: String?,
        liveStreamId: String?,
        playSessionId: String?,
    ): String? {
        if (liveStreamId == null || playSessionId == null) return null
        val response =
            run {
                api.mediaInfoApi
                    .getPostedPlaybackInfo(
                        channelId,
                        PlaybackInfoDto(
                            userId = Jdvr.userId,
                            deviceProfile = Jdvr.deviceProfile,
                            maxStreamingBitrate = 120_000_000,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                            enableTranscoding = true,
                            allowVideoStreamCopy = true,
                            allowAudioStreamCopy = true,
                            mediaSourceId = mediaSourceId,
                            liveStreamId = liveStreamId,
                            autoOpenLiveStream = false,
                        ),
                    ).content
            }
        val path = response.mediaSources.firstOrNull()?.transcodingUrl ?: return null
        return api.createUrl(path.replace(Regex("(?i)PlaySessionId=[^&]*"), "PlaySessionId=$playSessionId"))
    }

    /** Request the HLS playlist once, so the server starts the remux now (from tune-in). */
    suspend fun warmHls(url: String) =
        io {
            runCatching {
                // The server answers the variant playlist only once the first segments exist (~10 s).
                val http = Jdvr.http.newBuilder().readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
                val master = http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body.string() }
                val variant = master.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@runCatching
                val variantUrl = url.toHttpUrl().resolve(variant) ?: return@runCatching
                http.newCall(okhttp3.Request.Builder().url(variantUrl).build()).execute().close()
            }.onFailure { Timber.w(it, "Couldn't start HLS") }
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
        val seriesId: String? = null,
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
                seriesId = item.seriesId?.toString()?.replace("-", ""),
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

    // ---- Jellyfin libraries (movies, shows, archives) ------------------------------------------

    data class Library(
        val id: UUID,
        val name: String,
        val kind: String?,
    )

    /** One library item: a movie, show, season or episode, with the user's watched / resume state. */
    data class LibItem(
        val id: UUID,
        val name: String,
        val type: String,
        val year: Int?,
        val overview: String?,
        val runtimeMin: Long?,
        val played: Boolean,
        val resumeMs: Long,
        val unplayed: Int?,
        val season: Int?,
        val episode: Int?,
        val seriesId: UUID?,
        val seriesName: String?,
        val officialRating: String?,
    ) {
        val seasonEpisode: String? get() = if (season != null && episode != null) "S$season:E$episode" else null
    }

    private fun toLibItem(d: BaseItemDto) =
        LibItem(
            id = d.id,
            name = d.name ?: "",
            type = d.type.serialName,
            year = d.productionYear,
            overview = d.overview,
            runtimeMin = d.runTimeTicks?.let { it / 600_000_000L },
            played = d.userData?.played == true,
            resumeMs = (d.userData?.playbackPositionTicks ?: 0L) / 10_000L,
            unplayed = d.userData?.unplayedItemCount,
            season = d.parentIndexNumber,
            episode = d.indexNumber,
            seriesId = d.seriesId,
            seriesName = d.seriesName,
            officialRating = d.officialRating,
        )

    /** The user's libraries that hold videos (Live TV and playlists left out). */
    suspend fun libraries(): List<Library> =
        io {
            api.userViewsApi.getUserViews(userId = Jdvr.userId).content.items
                .filter { it.collectionType?.serialName !in setOf("livetv", "playlists", "music", "books", "photos") }
                .map { Library(it.id, it.name ?: "", it.collectionType?.serialName) }
        }

    /** A library's movies and shows, a page at a time: by name, or newest first. */
    suspend fun libraryItems(
        libraryId: UUID,
        newestFirst: Boolean,
        start: Int,
        count: Int,
    ): Pair<List<LibItem>, Int> =
        io {
            val r =
                api.itemsApi.getItems(
                    userId = Jdvr.userId,
                    parentId = libraryId,
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO),
                    sortBy = if (newestFirst) listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME) else listOf(ItemSortBy.SORT_NAME),
                    sortOrder = if (newestFirst) listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING, org.jellyfin.sdk.model.api.SortOrder.ASCENDING) else listOf(org.jellyfin.sdk.model.api.SortOrder.ASCENDING),
                    startIndex = start,
                    limit = count,
                    fields = listOf(ItemFields.OVERVIEW),
                    enableUserData = true,
                    enableTotalRecordCount = true,
                ).content
            r.items.map { toLibItem(it) } to (r.totalRecordCount ?: r.items.size)
        }

    suspend fun seasons(seriesId: UUID): List<LibItem> =
        io { api.tvShowsApi.getSeasons(seriesId = seriesId, userId = Jdvr.userId, enableUserData = true).content.items.map { toLibItem(it) } }

    /** A show's episodes, in order (one season, or all of them). */
    suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID?,
    ): List<LibItem> =
        io {
            api.tvShowsApi.getEpisodes(seriesId = seriesId, userId = Jdvr.userId, seasonId = seasonId, fields = listOf(ItemFields.OVERVIEW), enableUserData = true).content.items.map { toLibItem(it) }
        }

    suspend fun libItem(id: UUID): LibItem = io { toLibItem(api.userLibraryApi.getItem(id, Jdvr.userId).content) }

    suspend fun setPlayed(
        id: UUID,
        played: Boolean,
    ) = io {
        if (played) api.playStateApi.markPlayedItem(itemId = id, userId = Jdvr.userId) else api.playStateApi.markUnplayedItem(itemId = id, userId = Jdvr.userId)
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

    // ---- commercial / intro segments ----------------------------------------------------------

    /** A stretch to skip: "Commercial", "Intro", "Recap", "Preview" or "Outro". */
    data class Segment(
        val type: String,
        val startMs: Long,
        val endMs: Long,
    )

    /** Jellyfin's segments for a finished recording (Comskip's breaks, Intro Skipper's intros). */
    suspend fun segments(itemId: UUID): List<Segment> =
        io {
            runCatching {
                api.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                    Segment(
                        type = it.type.serialName,
                        startMs = it.startTicks / 10_000L,
                        endMs = it.endTicks / 10_000L,
                    )
                }
            }.getOrDefault(emptyList())
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

    /**
     * Commercials found so far in a recording still being made (Comskip's live scan); Jellyfin has
     * none for it until it finishes. First: still in progress.
     */
    suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>> {
        val r = plugin<LiveSegments>(HttpMethod.GET, "/ComskipSegments/Live/${itemId.toString().replace("-", "")}") ?: return false to emptyList()
        return r.inProgress to r.items.map { Segment(it.type, it.startTicks / 10_000L, it.endTicks / 10_000L) }
    }

    // ---- JellyDVR Live buffers ---------------------------------------------------------------

    @Serializable
    data class Buffer(
        @SerialName("Id") val id: String,
        @SerialName("ProgramName") val programName: String? = null,
        @SerialName("ItemId") val itemId: String? = null,
        @SerialName("StartedUtc") val startedUtc: String? = null,
        @SerialName("Recording") val recording: Boolean = false,
        @SerialName("Kept") val kept: Boolean = false,
    )

    @Serializable
    private data class Buffers(
        @SerialName("Buffers") val buffers: List<Buffer> = emptyList(),
    )

    /** The channel's buffers, oldest first; the last is the programme on now. */
    suspend fun buffers(channelId: UUID): List<Buffer> =
        plugin<Buffers>(HttpMethod.GET, "/JellyDvrLive/Channel/${channelId.toString().replace("-", "")}")?.buffers.orEmpty()

    /** Ask JellyDVR Live to start buffering the channel now (Pause), skipping its surfing delay. */
    suspend fun startBuffering(channelId: UUID): List<Buffer> =
        plugin<Buffers>(HttpMethod.POST, "/JellyDvrLive/Channel/${channelId.toString().replace("-", "")}/Start")?.buffers.orEmpty()

    @Serializable
    private data class BufferPrograms(
        @SerialName("ProgramIds") val programIds: List<String> = emptyList(),
    )

    /**
     * Guide programmes that are only a live TV buffer (JellyDVR Live's, not kept), in a plain
     * lower-case no-dash form: their timers look like recordings everywhere else.
     */
    suspend fun bufferPrograms(): Set<String> =
        plugin<BufferPrograms>(HttpMethod.GET, "/JellyDvrLive/Programs")?.programIds.orEmpty().map { it.plainId() }.toSet()

    /** The buffer an item is, if it is one (for Keep in the player). */
    suspend fun bufferOfItem(itemId: UUID): Buffer? = plugin(HttpMethod.GET, "/JellyDvrLive/Item/${itemId.toString().replace("-", "")}")

    suspend fun keepBuffer(
        bufferId: String,
        keep: Boolean,
    ): Buffer? = plugin(if (keep) HttpMethod.POST else HttpMethod.DELETE, "/JellyDvrLive/Keep/$bufferId")

    @Serializable
    data class Stopped(
        @SerialName("Stopped") val stopped: Int = 0,
    )

    /**
     * Stop pressed: JellyDVR Live stops buffering the channel now, freeing its tuner input (it
     * leaves kept buffers, real recordings, and channels someone else is watching). Runs on its
     * own scope so it finishes even as the player closes.
     */
    fun stopBuffering(channelId: UUID) {
        kotlinx.coroutines.MainScope().launch {
            plugin<Stopped>(HttpMethod.POST, "/JellyDvrLive/Channel/${channelId.toString().replace("-", "")}/Stop")
        }
    }

    // ---- Watch from start ----------------------------------------------------------------------

    @Serializable
    private data class GuideBadge(
        @SerialName("ProgramId") val programId: String = "",
        @SerialName("RecordingItemId") val recordingItemId: String? = null,
    )

    @Serializable
    private data class GuideBadges(
        @SerialName("Programs") val programs: List<GuideBadge> = emptyList(),
    )

    /** The recording of a programme being recorded now, as a library item (Comskip plugin). */
    suspend fun recordingOfProgram(programId: UUID): UUID? {
        val key = programId.toString().replace("-", "")
        return plugin<GuideBadges>(HttpMethod.GET, "/ComskipSegments/Badges/Guide")
            ?.programs
            ?.firstOrNull { it.programId.replace("-", "").equals(key, ignoreCase = true) }
            ?.recordingItemId
            ?.toUuid()
    }

    suspend fun tuners(channelId: UUID?): TunerStatus? = plugin(HttpMethod.GET, "/JellyDvrSchedule/Tuners", if (channelId == null) emptyMap() else mapOf("channelId" to channelId.toString()))
}

/** An id compared across APIs: some send dashes, some don't. */
fun String.plainId(): String = replace("-", "").lowercase()

/** Jellyfin ids come without dashes; UUID.fromString needs them. */
fun String.toUuid(): UUID? = toUUIDOrNull()
