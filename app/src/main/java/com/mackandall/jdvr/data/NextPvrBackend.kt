package com.mackandall.jdvr.data

import com.mackandall.jdvr.data.LiveTvRepository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import org.jellyfin.sdk.model.api.PlayMethod
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * A NextPVR server, through the JSON API its Kodi add-on (pvr.nextpvr) uses: a session signed in
 * with the PIN, then /service?method=…&sid=…. NextPVR's ids are numbers; the screens use UUIDs, so
 * a channel is UUID(CHANNEL, id) and a guide entry UUID(PROGRAM, id). What only Jellyfin and our
 * plugins have answers null or empty, as when a plugin isn't installed.
 */
class NextPvrBackend(
    private val baseUrl: String,
    // md5 of the PIN: what the sign-in hashes with the session's salt.
    private val pinMd5: String,
) : TvBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.systemDefault()
    private val signIn = Mutex()
    @Volatile private var sid: String? = null

    // Guide entries seen, by NextPVR id: a single entry's own lookup doesn't say its channel.
    private val seen = java.util.concurrent.ConcurrentHashMap<Int, Program>()

    // ---- the API ----------------------------------------------------------------------------

    private fun get(url: String): JsonElement =
        Jdvr.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("NextPVR answered ${r.code}")
            json.parseToJsonElement(r.body.string())
        }

    private fun serviceUrl(method: String, params: Map<String, Any?>, session: String?): String =
        baseUrl.toHttpUrl().newBuilder().addPathSegment("service").apply {
            addQueryParameter("method", method)
            addQueryParameter("format", "json")
            session?.let { addQueryParameter("sid", it) }
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build().toString()

    private suspend fun session(): String =
        sid ?: signIn.withLock {
            sid ?: withContext(Dispatchers.IO) { login(baseUrl, pinMd5) }.also { sid = it }
        }

    /** Calls a method; signs in again once when the session has expired. */
    private suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            var reply = get(serviceUrl(method, params, session()))
            if (failed(reply)) {
                sid = null
                reply = get(serviceUrl(method, params, session()))
            }
            if (failed(reply)) error("NextPVR: " + ((reply as JsonObject)["msg"]?.jsonPrimitive?.contentOrNull ?: "failed"))
            reply
        }

    private fun failed(reply: JsonElement) = (reply as? JsonObject)?.get("stat")?.jsonPrimitive?.contentOrNull.equals("Failed", ignoreCase = true)

    // ---- ids and fields ---------------------------------------------------------------------

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull

    private fun time(epochSeconds: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone)
    private fun epoch(t: LocalDateTime): Long = t.atZone(zone).toEpochSecond()

    private fun toProgram(o: JsonObject, channel: UUID?): Program {
        val id = o.int("id") ?: 0
        val genres = (o["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val season = o.int("season")
        val subtitle = o.str("subtitle")?.takeIf { it.isNotBlank() }
        val p =
            Program(
                id = programUuid(id),
                channelId = channel ?: o.int("channelId")?.let { channelUuid(it) } ?: seen[id]?.channelId ?: channelUuid(0),
                name = o.str("name") ?: "",
                rawEpisodeTitle = subtitle,
                start = time(o.long("start") ?: 0),
                end = time(o.long("end") ?: 0),
                // The search answer puts the episode title before the description again.
                overview = o.str("description")?.let { d -> subtitle?.let { d.removePrefix("$it: ") } ?: d }?.takeIf { it.isNotBlank() },
                isMovie = genres.any { it.equals("Movie", true) || it.equals("Movies", true) },
                isSeries = season != null || subtitle != null,
                isRepeat = o.bool("firstrun") == false,
                season = season,
                episode = o.int("episode"),
                timerId = o.int("recording_id")?.toString(),
                seriesTimerId = o.int("recurring_id")?.toString(),
            )
        seen[id] = p
        return p
    }

    // ---- channels and guide -----------------------------------------------------------------

    override suspend fun channels(): List<Channel> {
        val list = call("channel.list").jsonObject["channels"]?.jsonArray.orEmpty().map { it.jsonObject }
        val now =
            runCatching {
                call("channel.listings.current").jsonArray.associate { e ->
                    val c = e.jsonObject["channel"]!!.jsonObject
                    val id = c.int("channel_id") ?: 0
                    val at = System.currentTimeMillis() / 1000
                    id to c["listings"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { (it.long("start") ?: 0) <= at && (it.long("end") ?: 0) > at }?.let { toProgram(it, channelUuid(id)) }
                }
            }.getOrDefault(emptyMap())
        val sid = session()
        return list.map { c ->
            val id = c.int("channelId") ?: 0
            Channel(
                id = channelUuid(id),
                number = c.str("channelNumberFormated") ?: c.int("channelNumber")?.toString() ?: "",
                name = c.str("channelName") ?: "",
                logoUrl = if (c.bool("channelIcon") == true) serviceUrl("channel.icon", mapOf("channel_id" to id), sid) else null,
                now = now[id],
            )
        }.sortedWith(compareBy({ it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.name }))
    }

    override suspend fun programs(
        channelIds: List<UUID>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Program> =
        coroutineScope {
            channelIds.map { ch ->
                async {
                    runCatching {
                        call("channel.listings", mapOf("channel_id" to nextPvrId(ch), "start" to epoch(from), "end" to epoch(to)))
                            .jsonObject["listings"]?.jsonArray.orEmpty().map { toProgram(it.jsonObject, ch) }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().sortedBy { it.start }
        }

    override suspend fun program(id: UUID): Program? =
        runCatching { toProgram(call("channel.listing", mapOf("event_id" to nextPvrId(id))).jsonObject, null) }.getOrElse { seen[nextPvrId(id)] }

    override suspend fun searchGuide(term: String): List<Program> =
        call("channel.listings.search", mapOf("title" to term)).jsonObject["listings"]?.jsonArray.orEmpty().map { toProgram(it.jsonObject, null) }

    // A channel's logo, or a guide entry's art as NextPVR's web page shows it (landscape: fanart).
    override fun imageUrl(
        itemId: UUID,
        maxHeight: Int,
    ): String {
        val session = sid ?: return ""
        return when (itemId.mostSignificantBits) {
            CHANNEL -> serviceUrl("channel.icon", mapOf("channel_id" to nextPvrId(itemId)), session)
            PROGRAM -> serviceUrl("channel.show.artwork", mapOf("event_id" to nextPvrId(itemId), "fanart" to true), session)
            // A recording's poster (Recorded Shows' cards are portrait).
            RECORDING -> serviceUrl("recording.artwork", mapOf("recording_id" to nextPvrId(itemId), "with_placeholder" to true), session)
            else -> ""
        }
    }

    override fun backdropUrl(itemId: UUID): String {
        val session = sid ?: return ""
        return if (itemId.mostSignificantBits == RECORDING) {
            serviceUrl("recording.artwork", mapOf("recording_id" to nextPvrId(itemId), "fanart" to true), session)
        } else {
            imageUrl(itemId, 1080)
        }
    }

    override fun personImageUrl(personId: UUID): String = ""

    override fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String? = runCatching { UUID.fromString(itemId) }.getOrNull()?.let { imageUrl(it, maxHeight) }?.ifEmpty { null }

    // ---- recording --------------------------------------------------------------------------

    override suspend fun hasDvr(): Boolean = true

    override suspend fun record(
        programId: UUID,
        series: Boolean,
    ) {
        // A series: NextPVR's default rule for the show, as its web page's "Record series" makes.
        call(if (series) "recording.recurring.save" else "recording.save", mapOf("event_id" to nextPvrId(programId)))
    }

    override suspend fun cancelRecording(timerId: String) {
        call("recording.delete", mapOf("recording_id" to timerId))
    }

    override suspend fun cancelSeries(seriesTimerId: String) {
        call("recording.recurring.delete", mapOf("recurring_id" to seriesTimerId))
    }

    // ---- live TV ----------------------------------------------------------------------------

    /** NextPVR sends the channel as MPEG-TS straight from the tuner; the session is its client. */
    override suspend fun startLive(channelId: UUID): LivePlayback {
        val sid = session()
        val url =
            baseUrl.toHttpUrl().newBuilder().addPathSegment("live")
                .addQueryParameter("channeloid", nextPvrId(channelId).toString())
                .addQueryParameter("client", "jdvr-$sid")
                .addQueryParameter("sid", sid)
                .build().toString()
        return LivePlayback(channelId, url, mediaSourceId = null, liveStreamId = null, playSessionId = null, playMethod = PlayMethod.DIRECT_PLAY)
    }

    override suspend fun reportStart(p: LivePlayback) = Unit

    override suspend fun reportProgress(
        p: LivePlayback,
        paused: Boolean,
    ) = Unit

    override suspend fun closeLiveStream(id: String) = Unit

    /** Frees the tuner (closing the stream alone leaves it open a while). */
    override suspend fun reportStopped(p: LivePlayback) {
        runCatching { call("channel.stop") }
    }

    override suspend fun warmHls(url: String) = Unit

    // ---- recordings -------------------------------------------------------------------------

    /** A NextPVR recording, from recording.list. */
    private inner class Rec(val o: JsonObject) {
        val id = o.int("id") ?: 0
        val uuid get() = recordingUuid(id)
        val name = o.str("name") ?: ""
        // "S07E22 - The Bottle Deposit, Part 2": the episode title alone.
        val episodeTitle = o.str("subtitle")?.replace(Regex("^S\\d+E\\d+( - )?"), "")?.takeIf { it.isNotBlank() }
        val season = o.int("season")
        val episode = o.int("episode")
        val isSeries = season != null || episodeTitle != null
        val start = o.long("startTime") ?: 0
        val seconds = o.long("duration") ?: 0
        val status = o.str("status") ?: ""
        val recordingNow get() = status == "recording"
        val done get() = status == "ready" || status == "recording"
        val channelId = o.int("channelId")
        val positionS = o.long("playbackPosition") ?: 0
        val watched = o.bool("watched") == true
        val startUtc get() = Instant.ofEpochSecond(start).toString()
        val seasonEpisode get() = if (season != null && episode != null) "S$season:E$episode" else null

        fun libItem() =
            LibItem(
                id = uuid, name = episodeTitle ?: name, type = if (isSeries) "Episode" else "Movie", year = null,
                overview = o.str("desc")?.let { d -> episodeTitle?.let { d.removePrefix("$it: ") } ?: d }?.takeIf { it.isNotBlank() },
                runtimeMin = seconds / 60, played = watched, resumeMs = positionS * 1000, unplayed = null,
                season = season, episode = episode, seriesId = null, seriesName = name.takeIf { isSeries },
                officialRating = o.str("rating"),
            )
    }

    private suspend fun recordings(): List<Rec> =
        call("recording.list", mapOf("filter" to "all")).jsonObject["recordings"]?.jsonArray.orEmpty().map { Rec(it.jsonObject) }

    private suspend fun recording(id: UUID): Rec =
        call("recording.list", mapOf("recording_id" to nextPvrId(id))).jsonObject["recordings"]?.jsonArray?.firstOrNull()?.let { Rec(it.jsonObject) }
            ?: recordings().firstOrNull { it.uuid == id } ?: error("That recording is gone")

    // Channels by NextPVR id (number, name, logo), for the lists that show a recording's channel.
    @Volatile private var channelInfo: Map<Int, JsonObject>? = null

    private suspend fun channelInfo(): Map<Int, JsonObject> =
        channelInfo ?: call("channel.list").jsonObject["channels"]?.jsonArray.orEmpty().map { it.jsonObject }
            .associateBy { it.int("channelId") ?: 0 }.also { channelInfo = it }

    // A show is the recordings with its name.
    private fun showId(name: String) = "show:$name"

    override suspend fun recordedShows(filter: String): List<RecordingCard>? {
        if (filter == "archived") return emptyList()
        val done = recordings().filter { it.done }.sortedByDescending { it.start }
        return done.groupBy { if (it.isSeries) showId(it.name) else it.uuid.toString() }.map { (key, recs) ->
            val newest = recs.first()
            if (newest.isSeries) {
                RecordingCard(
                    type = "Series", id = key, imageId = newest.uuid.toString(), name = newest.name,
                    count = recs.size, unplayedCount = recs.count { !it.watched }, recordedUtc = newest.startUtc,
                    recordingCount = recs.count { it.recordingNow },
                )
            } else {
                RecordingCard(
                    type = "Movie", id = key, name = newest.name, count = 1, unplayedCount = if (newest.watched) 0 else 1,
                    recordedUtc = newest.startUtc, recordingCount = if (newest.recordingNow) 1 else 0,
                )
            }
        }
    }

    override suspend fun showRecordings(
        seriesId: String,
        filter: String,
    ): List<EpisodeRecording>? {
        if (filter == "archived") return emptyList()
        return recordings().filter { it.done && showId(it.name) == seriesId }.sortedByDescending { it.start }.map { r ->
            EpisodeRecording(
                id = r.uuid.toString(), name = r.episodeTitle ?: r.name, season = r.season, episode = r.episode, played = r.watched,
                recordedUtc = r.startUtc, minutes = (r.seconds / 60).toInt(), recording = r.recordingNow,
            )
        }
    }

    override suspend fun recordingsByDate(filter: String): DateRecordings? {
        if (filter == "archived") return DateRecordings()
        val channels = channelInfo()
        return DateRecordings(
            recordings =
                recordings().filter { it.done }.sortedByDescending { it.start }.map { r ->
                    DateRecording(
                        id = r.uuid.toString(), type = if (r.isSeries) "Episode" else "Movie", imageId = r.uuid.toString(), title = r.name,
                        episodeTitle = r.episodeTitle, season = r.season, episode = r.episode, recordedUtc = r.startUtc,
                        minutes = (r.seconds / 60).toInt(), played = r.watched, playedPercent = if (r.seconds > 0) (r.positionS * 100 / r.seconds).toInt() else 0,
                        recording = r.recordingNow, overview = r.libItem().overview,
                        channel =
                            r.channelId?.let { id ->
                                val c = channels[id]
                                DateChannel(channelUuid(id).toString(), c?.str("channelName") ?: r.o.str("channel") ?: "", c?.str("channelNumberFormated"), c?.bool("channelIcon") == true)
                            },
                    )
                },
        )
    }

    override suspend fun libItem(id: UUID): LibItem = recording(id).libItem()

    override suspend fun details(id: UUID): ItemDetails {
        if (id.mostSignificantBits != RECORDING) unsupported()
        val r = recording(id)
        // recording.info: a list of key/value pairs ("0:Video Codec", "Size", "0:Audio Desc"…).
        val info =
            runCatching {
                call("recording.info", mapOf("recording_id" to r.id)).jsonArray.associate { e -> e.jsonObject.str("key").orEmpty() to e.jsonObject.str("value").orEmpty() }
            }.getOrDefault(emptyMap())
        return ItemDetails(
            item = r.libItem(),
            tagline = null,
            genres = (r.o["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            studios = emptyList(),
            communityRating = null,
            criticRating = null,
            premiere = r.o.str("original")?.let { runCatching { LocalDateTime.parse(it).toLocalDate() }.getOrNull() },
            backdropOf = r.uuid,
            people = emptyList(),
            video = listOfNotNull(info["0:Video Codec"], info["0:Video FPS"]?.let { "$it fps" }).joinToString(" · ").ifEmpty { null },
            audio = info.filterKeys { it.endsWith(":Audio Desc") }.values.toList(),
            subtitles = info.filterKeys { it.endsWith(":Subtitle") }.values.toList(),
            file = listOfNotNull(info["Filename"]?.substringAfterLast('.')?.uppercase(), info["Size"]).joinToString(" · ").ifEmpty { null },
            location = "NextPVR",
            recordedAt = time(r.start),
            canDelete = true,
        )
    }

    /** The recording as NextPVR streams it (also while it's still recording; seeking works). */
    override suspend fun startItem(itemId: UUID): ItemPlayback {
        val r = recording(itemId)
        val sid = session()
        // Still recording: the file grows, and the player, reading its end for the duration, would
        // chase it for ever. It gets the size it has now instead (see [snapshotLength]); at that
        // end the player opens it again.
        val info = if (r.recordingNow) runCatching { call("recording.info", mapOf("recording_id" to r.id)).jsonArray.associate { e -> e.jsonObject.str("key").orEmpty() to e.jsonObject.str("value").orEmpty() } }.getOrNull() else null
        val url =
            baseUrl.toHttpUrl().newBuilder().addPathSegment("live")
                .addQueryParameter("recording", r.id.toString())
                .addQueryParameter("client", "jdvr-$sid")
                .addQueryParameter("sid", sid)
                .apply { info?.get("Bytes")?.toLongOrNull()?.let { addQueryParameter(SNAPSHOT, it.toString()) } }
                .build().toString()
        return ItemPlayback(
            itemId = itemId,
            name = listOfNotNull(r.name, r.episodeTitle).joinToString(" - "),
            url = url,
            mediaSourceId = null,
            playSessionId = null,
            playMethod = PlayMethod.DIRECT_PLAY,
            resumeMs = r.positionS * 1000,
            durationMs = (info?.get("DurationSeconds")?.toLongOrNull() ?: r.seconds) * 1000,
        )
    }

    /** Where it was left: NextPVR keeps one position per recording (its web page saves after 15 s). */
    override suspend fun reportItem(
        p: ItemPlayback,
        positionMs: Long,
        paused: Boolean,
        event: String,
    ) {
        if (event == "start" || positionMs < 15_000) return
        runCatching { call("recording.watched.set", mapOf("recording_id" to nextPvrId(p.itemId), "position" to positionMs / 1000)) }
    }

    override suspend fun deleteRecording(itemId: UUID): DeletedRecording {
        call("recording.delete", mapOf("recording_id" to nextPvrId(itemId)))
        return DeletedRecording(deleted = 1)
    }

    override suspend fun deleteItem(itemId: UUID) {
        deleteRecording(itemId)
    }

    // No commercials from NextPVR yet; whether it's still recording (the player says so, and goes on).
    override suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>> =
        (itemId.mostSignificantBits == RECORDING && runCatching { recording(itemId).recordingNow }.getOrDefault(false)) to emptyList()

    override suspend fun recordingOfProgram(programId: UUID): UUID? =
        recordings().firstOrNull { it.done && it.o.int("epgEventId") == nextPvrId(programId) }?.uuid

    // ---- series recordings ------------------------------------------------------------------

    /** NextPVR's recurring rules, in the shape the Series screen shows (padding there is minutes). */
    override suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> =
        call("recording.recurring.list").jsonObject["recurrings"]?.jsonArray.orEmpty().map { it.jsonObject }.map { r ->
            val period = r.str("period").orEmpty()
            org.jellyfin.sdk.model.api.SeriesTimerInfoDto(
                id = r.int("id")?.toString(),
                name = r.str("name"),
                channelName = r.str("channel")?.takeIf { (r.int("channelID") ?: 0) != 0 },
                recordAnyChannel = (r.int("channelID") ?: 0) == 0 || period.contains("all channels", true),
                recordAnyTime = true,
                recordNewOnly = period.contains("new", true),
                skipEpisodesInLibrary = false,
                keepUpTo = r.int("keep") ?: 0,
                prePaddingSeconds = (r.int("prePadding") ?: 0) * 60,
                postPaddingSeconds = (r.int("postPadding") ?: 0) * 60,
                isPrePaddingRequired = false,
                isPostPaddingRequired = false,
                priority = r.int("priority") ?: 0,
                keepUntil = org.jellyfin.sdk.model.api.KeepUntil.UNTIL_DELETED,
                dayPattern = null,
                days = emptyList(),
            )
        }

    override suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto): Unit = error("Change this series in NextPVR's web page for now")

    // ---- scheduled recordings ---------------------------------------------------------------

    override suspend fun upcoming(): List<Upcoming>? {
        val channels = runCatching { channelInfo() }.getOrDefault(emptyMap())
        return recordings().filter { it.status == "pending" || it.status == "recording" || it.status == "conflict" }.sortedBy { it.start }.map { r ->
            Upcoming(
                timerId = r.id.toString(),
                programId = r.o.int("epgEventId")?.let { programUuid(it).toString() },
                name = r.name,
                episodeTitle = r.episodeTitle,
                seasonEpisode = r.seasonEpisode,
                start = r.startUtc,
                end = Instant.ofEpochSecond(r.start + r.seconds).toString(),
                channelNumber = r.channelId?.let { channels[it]?.str("channelNumberFormated") },
                channelName = r.o.str("channel"),
                kind = if ((r.o.int("recurringParent") ?: 0) > 0) "series" else "oneoff",
                recordingNow = r.recordingNow,
            )
        }
    }

    override suspend fun schedule(): List<Upcoming>? = upcoming()

    private fun unsupported(): Nothing = error("Not available on NextPVR")

    // ---- what only Jellyfin and our plugins have ------------------------------------------

    override suspend fun libraries(): List<Library> = emptyList()

    override suspend fun libraryItems(
        libraryId: UUID,
        newestFirst: Boolean,
        start: Int,
        count: Int,
    ): Pair<List<LibItem>, Int> = emptyList<LibItem>() to 0

    override suspend fun libraryLetters(libraryId: UUID): List<String> = emptyList()

    override suspend fun searchLibrary(term: String): List<LibItem> = emptyList()

    override suspend fun seasons(seriesId: UUID): List<LibItem> = emptyList()

    override suspend fun episodes(
        seriesId: UUID,
        seasonId: UUID?,
    ): List<LibItem> = emptyList()

    override suspend fun latestIn(
        libraryId: UUID,
        limit: Int,
    ): List<LibItem>? = null

    override suspend fun setPlayed(
        id: UUID,
        played: Boolean,
    ) = Unit

    override suspend fun markSymbols(): MarkSymbols? = null

    override suspend fun hasEpisodeHistory(): Boolean = false

    override suspend fun scheduleState(programId: UUID): ScheduleState? = null

    override suspend fun airings(
        itemId: UUID?,
        programId: UUID?,
    ): AiringsResult? = null

    override suspend fun rerecordState(itemId: UUID): RerecordState? = null

    override suspend fun rerecord(
        itemId: UUID,
        programId: String?,
        reason: String?,
    ): Message? = null

    override suspend fun cancelRerecord(itemId: UUID): Boolean = false

    override suspend fun favouriteForItem(itemId: UUID): FavouriteFor? = null

    override suspend fun trash(): TrashList? = null

    override suspend fun restoreFromTrash(id: String): Boolean = false

    override suspend fun deleteFromTrash(id: String): Boolean = false

    override suspend fun emptyTrash(): Int? = null

    override suspend fun trashDays(): Int? = null

    override suspend fun activeRecordings(itemId: UUID): ActiveRecordings? = null

    override suspend fun stopRecording(recordingId: String): Boolean = false

    override suspend fun deleteShowRecordings(
        seriesId: UUID,
        dryRun: Boolean,
        archive: Boolean,
    ): ShowDelete? = null

    override suspend fun deviceRights(): DeviceRights? = null

    override suspend fun ehProgram(programId: UUID): EhProgram? = null

    override suspend fun setProgramWatched(
        programId: UUID,
        watched: Boolean,
    ): kotlinx.serialization.json.JsonObject? = null

    override suspend fun forgetProgram(programId: UUID): kotlinx.serialization.json.JsonObject? = null

    override suspend fun forgetShow(itemId: UUID, dryRun: Boolean): ShowForget? = null

    override suspend fun forgetRecording(itemId: UUID): Forgot? = null

    override suspend fun commercialBadges(ids: List<UUID>): Map<String, CommercialBadge> = emptyMap()

    override suspend fun scanCommercials(itemId: UUID): Message? = null

    override suspend fun commercialStatus(itemId: UUID): CommercialStatus? = null

    override suspend fun removeCommercials(itemId: UUID): Cleared? = null

    override suspend fun fixAudioFormat(itemId: UUID): Message? = null

    override suspend fun setProgramStart(itemId: UUID, seconds: Double, keyframe: Boolean): ProgramStartResult? = null

    override suspend fun keyframes(itemId: UUID, seconds: Double): Keyframes? = null

    override suspend fun frame(itemId: UUID, seconds: Double, width: Int): androidx.compose.ui.graphics.ImageBitmap? = null

    override suspend fun rememberStartCard(itemId: UUID, seconds: Double): ProgramStartResult? = null

    override suspend fun editing(): Editing? = null

    override suspend fun programStartStatus(itemId: UUID): ProgramStartStatus? = null

    override suspend fun clearProgramStart(itemId: UUID): ProgramStartResult? = null

    override suspend fun undoTrim(itemId: UUID): ProgramStartResult? = null

    override suspend fun findProgramStart(itemId: UUID): ProgramStartResult? = null

    override suspend fun cuts(): List<CutJob>? = null

    override suspend fun stopCut(itemId: UUID): Message? = null

    override suspend fun moving(): List<MoveProgress>? = null

    override suspend fun archiveChoices(itemId: UUID): ArchiveChoices? = null

    override suspend fun archive(itemId: UUID, target: String): Message? = null

    override suspend fun recordInstead(programId: UUID): Message? = null

    override suspend fun addFavourite(programId: UUID): Message? = null

    override suspend fun removeFavourite(ruleId: String): Message? = null

    override suspend fun favourites(): FavouritesList? = null

    override suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message? = null

    override suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message? = null

    override suspend fun jellyfinRecordings(): List<RecordingCard>? = null

    override suspend fun recentlyAdded(limit: Int): List<RecordingCard>? = null

    override suspend fun segments(itemId: UUID): List<Segment> = emptyList()

    override suspend fun buffers(channelId: UUID): List<Buffer> = emptyList()

    override suspend fun startBuffering(channelId: UUID): List<Buffer> = emptyList()

    override suspend fun bufferPrograms(): Set<String> = emptySet()

    override suspend fun bufferOfItem(itemId: UUID): Buffer? = null

    override suspend fun keepBuffer(
        bufferId: String,
        keep: Boolean,
    ): Buffer? = null

    override fun stopBuffering(channelId: UUID) = Unit

    override suspend fun guideScanning(): Map<String, Int?> = emptyMap()

    override suspend fun homeExtras(): HomeExtras? = null

    override suspend fun tuners(channelId: UUID?): TunerStatus? = null

    companion object {
        // The URL parameter carrying a still-recording file's size when playback opened.
        private const val SNAPSHOT = "jdvrlen"

        /**
         * Makes a growing file look as long as it was when playback opened (the [SNAPSHOT] in its
         * URL): replies say that length, and stop there. Other requests pass untouched.
         */
        val snapshotLength =
            okhttp3.Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val len = chain.request().url.queryParameter(SNAPSHOT)?.toLongOrNull() ?: return@Interceptor response
                val body = response.body
                val (from, to) =
                    if (response.code == 206) {
                        val range = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('/') ?: return@Interceptor response
                        (range.substringBefore('-').toLongOrNull() ?: 0L) to (range.substringAfter('-').toLongOrNull() ?: (len - 1))
                    } else if (response.code == 200) {
                        0L to len - 1
                    } else {
                        return@Interceptor response
                    }
                val end = minOf(to, len - 1)
                val count = (end - from + 1).coerceAtLeast(0)
                val capped =
                    object : okhttp3.ResponseBody() {
                        override fun contentType() = body.contentType()

                        override fun contentLength() = count

                        private val capped by lazy {
                            object : okio.ForwardingSource(body.source()) {
                                var left = count

                                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                    if (left <= 0) return -1
                                    val n = super.read(sink, minOf(byteCount, left))
                                    if (n > 0) left -= n
                                    return n
                                }
                            }.buffer()
                        }

                        override fun source(): okio.BufferedSource = capped

                        override fun close() = body.close()
                    }
                response.newBuilder()
                    .header("Content-Length", count.toString())
                    .apply { if (response.code == 206) header("Content-Range", "bytes $from-$end/$len") }
                    .body(capped)
                    .build()
            }

        private const val CHANNEL = 0x4E50565200000001L
        private const val PROGRAM = 0x4E50565200000002L
        private const val RECORDING = 0x4E50565200000003L

        fun channelUuid(id: Int) = UUID(CHANNEL, id.toLong())

        fun programUuid(id: Int) = UUID(PROGRAM, id.toLong())

        fun recordingUuid(id: Int) = UUID(RECORDING, id.toLong())

        fun nextPvrId(id: UUID): Int = id.leastSignificantBits.toInt()

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Signs in: a session and its salt, then md5(":" + md5(PIN) + ":" + salt). Returns the session id. */
        fun login(
            baseUrl: String,
            pinMd5: String,
        ): String {
            val get = { url: String ->
                Jdvr.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("NextPVR answered ${r.code}")
                    Json.parseToJsonElement(r.body.string()).jsonObject
                }
            }
            val base = baseUrl.toHttpUrl()
            val start = get(base.newBuilder().addPathSegment("service").addQueryParameter("method", "session.initiate").addQueryParameter("ver", "1.0").addQueryParameter("device", "JDVR").addQueryParameter("format", "json").build().toString())
            val sid = start["sid"]?.jsonPrimitive?.contentOrNull ?: error("No NextPVR server answers at $baseUrl")
            val salt = start["salt"]?.jsonPrimitive?.contentOrNull ?: error("No NextPVR server answers at $baseUrl")
            val reply = get(base.newBuilder().addPathSegment("service").addQueryParameter("method", "session.login").addQueryParameter("sid", sid).addQueryParameter("md5", md5(":$pinMd5:$salt")).addQueryParameter("format", "json").build().toString())
            if (reply["stat"]?.jsonPrimitive?.contentOrNull != "ok") error("Wrong PIN")
            return sid
        }

        /** Whether a NextPVR server answers at this address (it starts a session for anyone). */
        fun answers(baseUrl: String): Boolean =
            runCatching {
                val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("service").addQueryParameter("method", "session.initiate").addQueryParameter("ver", "1.0").addQueryParameter("device", "JDVR").addQueryParameter("format", "json").build().toString()
                Jdvr.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    r.isSuccessful && Json.parseToJsonElement(r.body.string()).jsonObject["salt"] != null
                }
            }.getOrDefault(false)
    }
}
