package com.mackandall.jdvr.data

import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import timber.log.Timber

/**
 * The Jellyfin server: its own API for channels, the guide, playback, libraries and series
 * recordings, and our plugins for the rest (see [TvBackend]).
 */
class JellyfinBackend private constructor(
    private val plugins: PluginClient,
) : TvBackend,
    RecordingHistory by EpisodeHistoryPlugin(plugins),
    Commercials by ComskipPlugin(plugins),
    Archiving by SpacePlugin(plugins),
    RecordingRules by SchedulePlugin(plugins),
    LiveBuffers by LivePlugin(plugins) {
    constructor() : this(PluginClient { Jdvr.api ?: error("Not signed in") })

    // The Jellyfin SDK reads replies on the calling thread: never the main one (Android forbids it).
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private val api: ApiClient get() = Jdvr.api ?: error("Not signed in")

    // The guide's channel groups: every channel, or the user's favourite channels.
    override suspend fun channelGroups(): List<String> {
        val any = io { api.liveTvApi.getLiveTvChannels(GetLiveTvChannelsRequest(userId = Jdvr.userId, isFavorite = true, limit = 1)).content.items }
        return if (any.isEmpty()) emptyList() else listOf(ALL_CHANNELS, FAVOURITE_CHANNELS)
    }

    // Favourites with none left (unfavourited in the web client) would leave the guide and Home
    // empty, as if the server had no channels: every channel then.
    override suspend fun channels(): List<Channel> =
        if (Jdvr.prefs.channelGroup == FAVOURITE_CHANNELS) channelList(favourites = true).ifEmpty { channelList(favourites = false) } else channelList(favourites = false)

    override suspend fun allChannels(): List<Channel> = channelList(favourites = false)

    private suspend fun channelList(favourites: Boolean): List<Channel> =
        io {
        val items =
            api.liveTvApi
                .getLiveTvChannels(
                    GetLiveTvChannelsRequest(
                        userId = Jdvr.userId,
                        isFavorite = if (favourites) true else null,
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

    override suspend fun programs(
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

    override suspend fun program(id: UUID): Program? = io { api.liveTvApi.getProgram(id.toString(), Jdvr.userId).content.let { toProgram(it) } }

    override fun imageUrl(
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

    override suspend fun record(
        programId: UUID,
        series: Boolean,
    ): Unit = io {
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

    override suspend fun cancelRecording(timerId: String): Unit =
        io {
        val apiId = runCatching {
            api.liveTvApi.getTimers().content.items.firstOrNull { it.id == timerId || it.externalId == timerId }?.id
        }.getOrNull() ?: timerId
        api.liveTvApi.cancelTimer(apiId)
    }

    override suspend fun cancelSeries(seriesTimerId: String): Unit =
        io {
        api.liveTvApi.cancelSeriesTimer(seriesTimerId)
    }

    override suspend fun startLive(channelId: UUID): LivePlayback =
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
        val hls = if (method == PlayMethod.TRANSCODE) null else attemptResult { hlsOf(channelId, source.id, source.liveStreamId, response.playSessionId) }.onFailure { Timber.w(it, "No HLS for %s", channelId) }.getOrNull()
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

    override suspend fun warmHls(url: String): Unit =
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

    override suspend fun startItem(itemId: UUID): ItemPlayback =
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
                subtitles =
                    source.mediaStreams.orEmpty()
                        .filter { it.type == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE && it.deliveryMethod == org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null }
                        .map { st ->
                            ExternalSubtitle(
                                url = api.createUrl(st.deliveryUrl!!),
                                mimeType =
                                    when (st.codec?.lowercase()) {
                                        "vtt", "webvtt" -> "text/vtt"
                                        "ass", "ssa" -> "text/x-ssa"
                                        "ttml" -> "application/ttml+xml"
                                        else -> "application/x-subrip"
                                    },
                                language = st.language,
                                label = st.displayTitle,
                            )
                        },
            )
        }

    override suspend fun reportItem(
        p: ItemPlayback,
        positionMs: Long,
        paused: Boolean,
        event: String,
    ): Unit = io {
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

    override suspend fun reportStart(p: LivePlayback): Unit =
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

    override suspend fun reportProgress(
        p: LivePlayback,
        paused: Boolean,
    ): Unit = io {
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

    override suspend fun closeLiveStream(id: String): Unit = io { api.mediaInfoApi.closeLiveStream(id) }

    override suspend fun reportStopped(p: LivePlayback): Unit =
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

    override suspend fun libraries(): List<Library> =
        io {
            api.userViewsApi.getUserViews(userId = Jdvr.userId).content.items
                .filter { it.collectionType?.serialName !in setOf("livetv", "playlists", "music", "books", "photos") }
                .map { Library(it.id, it.name ?: "", it.collectionType?.serialName) }
        }

    override suspend fun libraryItems(
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

    override suspend fun libraryLetters(libraryId: UUID): List<String> =
        io {
            api.itemsApi.getItems(
                userId = Jdvr.userId,
                parentId = libraryId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.ASCENDING),
                fields = listOf(ItemFields.SORT_NAME),
                enableImages = false,
                enableUserData = false,
            ).content.items.map { letterOf(it.sortName ?: it.name) }
        }

    override suspend fun searchLibrary(term: String): List<LibItem> =
        io {
            api.itemsApi.getItems(
                userId = Jdvr.userId,
                searchTerm = term,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO),
                limit = 60,
                fields = listOf(ItemFields.OVERVIEW),
                enableUserData = true,
            ).content.items.map { toLibItem(it) }
        }

    override suspend fun searchGuide(term: String): List<Program> =
        io {
            val now = LocalDateTime.now()
            api.itemsApi.getItems(
                userId = Jdvr.userId,
                searchTerm = term,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.LIVE_TV_PROGRAM),
                limit = 200,
                fields = listOf(ItemFields.OVERVIEW),
                enableImages = false,
            ).content.items.mapNotNull { toProgram(it) }.filter { it.end.isAfter(now) }.sortedBy { it.start }
        }

    override suspend fun seasons(seriesId: UUID): List<LibItem> =
        io { api.tvShowsApi.getSeasons(seriesId = seriesId, userId = Jdvr.userId, enableUserData = true).content.items.map { toLibItem(it) } }

    override suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID?,
    ): List<LibItem> =
        io {
            api.tvShowsApi.getEpisodes(seriesId = seriesId, userId = Jdvr.userId, seasonId = seasonId, fields = listOf(ItemFields.OVERVIEW), enableUserData = true).content.items.map { toLibItem(it) }
        }

    override suspend fun details(id: UUID): ItemDetails =
        io {
            val d = api.userLibraryApi.getItem(id, Jdvr.userId).content
            val source = d.mediaSources?.firstOrNull()
            val streams = source?.mediaStreams ?: d.mediaStreams.orEmpty()
            val video = streams.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }
            val size = source?.size?.let { b -> if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB" }
            ItemDetails(
                item = toLibItem(d),
                tagline = d.taglines?.firstOrNull(),
                genres = d.genres.orEmpty(),
                studios = d.studios?.mapNotNull { it.name }.orEmpty(),
                communityRating = d.communityRating,
                criticRating = d.criticRating,
                premiere = d.premiereDate?.toLocalDate(),
                // The item's own backdrop, else its show's.
                backdropOf = if (!d.backdropImageTags.isNullOrEmpty()) d.id else d.parentBackdropItemId,
                people = d.people.orEmpty().map { Person(it.id, it.name ?: "", it.role?.takeIf { r -> r.isNotBlank() }, it.type.serialName, it.primaryImageTag != null) },
                video =
                    video?.let { v ->
                        listOfNotNull(v.displayTitle ?: v.codec?.uppercase(), v.realFrameRate?.let { "%.2f fps".format(it).replace(".00", "") }).joinToString(" · ")
                    },
                audio = streams.filter { it.type == org.jellyfin.sdk.model.api.MediaStreamType.AUDIO }.mapNotNull { it.displayTitle ?: it.codec },
                subtitles = streams.filter { it.type == org.jellyfin.sdk.model.api.MediaStreamType.SUBTITLE }.mapNotNull { it.displayTitle ?: it.language },
                file = listOfNotNull(source?.container?.uppercase(), size, source?.bitrate?.let { "${it / 1_000_000} Mbps" }).joinToString(" · ").ifEmpty { null },
                location =
                    source?.path?.let { path ->
                        when {
                            path.startsWith("/mnt/Internal/JellyFinRecordings") -> "Live recordings folder"
                            path.startsWith("/mnt/") -> path.removePrefix("/mnt/").substringBefore('/')
                            else -> null
                        }
                    },
                // The SDK already gives local time; the file's creation is when recording started.
                recordedAt = d.dateCreated?.takeIf { source?.path?.startsWith("/mnt/Internal/JellyFinRecordings") == true },
                canDelete = d.canDelete != false,
            )
        }

    override fun backdropUrl(itemId: UUID): String = api.imageApi.getItemImageUrl(itemId, ImageType.BACKDROP, maxWidth = 1920)

    override fun personImageUrl(personId: UUID): String = api.imageApi.getItemImageUrl(personId, ImageType.PRIMARY, maxHeight = 240)

    override suspend fun libItem(id: UUID): LibItem = io { toLibItem(api.userLibraryApi.getItem(id, Jdvr.userId).content) }

    override suspend fun latestIn(
        libraryId: UUID,
        limit: Int,
    ): List<LibItem>? =
        attempt {
            io { api.userLibraryApi.getLatestMedia(userId = Jdvr.userId, parentId = libraryId, limit = limit).content.map(::toLibItem) }
        }

    override suspend fun setPlayed(
        id: UUID,
        played: Boolean,
    ): Unit = io {
        if (played) api.playStateApi.markPlayedItem(itemId = id, userId = Jdvr.userId) else api.playStateApi.markUnplayedItem(itemId = id, userId = Jdvr.userId)
    }

    override suspend fun deleteItem(itemId: UUID): Unit = io { api.libraryApi.deleteItem(itemId) }

    override fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String? = itemId.toUuid()?.let { imageUrl(it, maxHeight) }

    override suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto): Unit = io { api.liveTvApi.updateSeriesTimer(t.id!!, t) }

    override suspend fun seriesScheduled(): Map<String, SeriesScheduled> =
        io { api.liveTvApi.getTimers().content.items }
            .filter { it.seriesTimerId != null && it.startDate != null }
            .groupBy { it.seriesTimerId!!.plainId() }
            .mapValues { (_, timers) ->
                val next = timers.minBy { it.startDate!! }
                SeriesScheduled(timers.size, next.startDate!!, next.channelName)
            }

    override suspend fun newSeriesTimer(programId: UUID): org.jellyfin.sdk.model.api.SeriesTimerInfoDto = io { api.liveTvApi.getDefaultTimer(programId.toString()).content }

    override suspend fun createSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto): Unit = io { api.liveTvApi.createSeriesTimer(t) }

    override suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> =
        io { api.liveTvApi.getSeriesTimers().content.items }

    override suspend fun schedule(): List<Upcoming>? = upcoming() ?: jellyfinTimers()

    override suspend fun hasDvr(): Boolean {
        val channels = attempt { io { api.liveTvApi.getLiveTvChannels(userId = Jdvr.userId, limit = 1).content.totalRecordCount } } ?: return true
        if (channels > 0) return true
        val recordings = attempt { io { api.liveTvApi.getRecordings(userId = Jdvr.userId, limit = 1).content.totalRecordCount } } ?: return true
        return recordings > 0
    }

    private suspend fun jellyfinTimers(): List<Upcoming>? =
        attempt {
            io { api.liveTvApi.getTimers().content.items }.mapNotNull { t ->
                val start = t.startDate ?: return@mapNotNull null
                val end = t.endDate ?: return@mapNotNull null
                val p = t.programInfo
                Upcoming(
                    timerId = t.id ?: return@mapNotNull null,
                    programId = t.programId,
                    name = t.name ?: p?.name ?: "",
                    episodeTitle = p?.episodeTitle,
                    seasonEpisode = if (p?.parentIndexNumber != null && p.indexNumber != null) "S${p.parentIndexNumber}:E${p.indexNumber}" else null,
                    isRepeat = p?.isRepeat == true,
                    start = start.atZone(java.time.ZoneId.systemDefault()).toInstant().toString(),
                    end = end.atZone(java.time.ZoneId.systemDefault()).toInstant().toString(),
                    channelNumber = p?.channelNumber,
                    channelName = t.channelName,
                    kind = if (t.seriesTimerId != null) "series" else "oneoff",
                    recordingNow = t.status == org.jellyfin.sdk.model.api.RecordingStatus.IN_PROGRESS,
                )
            }.sortedBy { it.start }
        }

    override suspend fun jellyfinRecordings(): List<RecordingCard>? =
        attempt {
            io {
                api.liveTvApi.getRecordings(
                    userId = Jdvr.userId,
                    fields = listOf(org.jellyfin.sdk.model.api.ItemFields.DATE_CREATED),
                ).content.items
            }.sortedByDescending { it.dateCreated }
                .map { d ->
                    RecordingCard(
                        type = d.type.serialName,
                        id = d.id.toString(),
                        imageId = d.seriesId?.toString(),
                        name = d.seriesName ?: d.name ?: "",
                        subtitle =
                            if (d.seriesName != null) {
                                listOfNotNull(d.parentIndexNumber?.let { s -> d.indexNumber?.let { "S$s:E$it" } }, d.episodeTitle ?: d.name).joinToString(" · ")
                            } else {
                                d.productionYear?.toString()
                            },
                        count = 1,
                        unplayedCount = if (d.userData?.played == true) 0 else 1,
                    )
                }
        }

    override suspend fun recentlyAdded(limit: Int): List<RecordingCard>? =
        attempt {
            io {
                api.userLibraryApi.getLatestMedia(
                    userId = Jdvr.userId,
                    limit = limit,
                    groupItems = false,
                    isPlayed = false,
                ).content
            }.map { d ->
                RecordingCard(
                    type = d.type.serialName,
                    id = d.id.toString(),
                    imageId = (if (d.type == org.jellyfin.sdk.model.api.BaseItemKind.EPISODE) d.seriesId else null)?.toString(),
                    name = d.seriesName ?: d.name ?: "",
                    subtitle =
                        if (d.seriesName != null) {
                            listOfNotNull(d.parentIndexNumber?.let { s -> d.indexNumber?.let { "S$s:E$it" } }, d.name).joinToString(" · ")
                        } else {
                            d.productionYear?.toString()
                        },
                    count = 1,
                    unplayedCount = 1,
                )
            }
        }

    override suspend fun segments(itemId: UUID): List<Segment> =
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

    /**
     * Deletes a recording through Episode History: a partial one takes the episode's other
     * partial versions with it (the pieces of an interrupted recording), so it records again;
     * a full one goes alone. Without that endpoint, Jellyfin's own delete. Returns how many
     * files went.
     */
    override suspend fun deleteRecording(itemId: UUID): DeletedRecording =
        plugins.call<DeletedRecording>(HttpMethod.DELETE, "/EpisodeHistory/Item/${itemId.plain()}/Recording")
            ?: deleteItem(itemId).let { DeletedRecording(deleted = 1) }
}
