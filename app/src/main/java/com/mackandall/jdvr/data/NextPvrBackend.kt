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
            else -> ""
        }
    }

    override fun backdropUrl(itemId: UUID): String = imageUrl(itemId, 1080)

    override fun personImageUrl(personId: UUID): String = ""

    override fun imageUrl(
        itemId: String,
        maxHeight: Int,
    ): String? = null

    // ---- recording --------------------------------------------------------------------------

    override suspend fun hasDvr(): Boolean = true

    override suspend fun record(
        programId: UUID,
        series: Boolean,
    ) {
        if (series) error("Record series isn't on NextPVR in JDVR yet")
        call("recording.save", mapOf("event_id" to nextPvrId(programId)))
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

    private fun unsupported(): Nothing = error("Not available on NextPVR")

    // ---- what only Jellyfin and our plugins have ------------------------------------------

    override suspend fun startItem(itemId: UUID): ItemPlayback = unsupported()

    override suspend fun reportItem(
        p: ItemPlayback,
        positionMs: Long,
        paused: Boolean,
        event: String,
    ) = Unit

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

    override suspend fun details(id: UUID): ItemDetails = unsupported()

    override suspend fun libItem(id: UUID): LibItem = unsupported()

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

    override suspend fun deleteItem(itemId: UUID) = Unit

    override suspend fun trash(): TrashList? = null

    override suspend fun restoreFromTrash(id: String): Boolean = false

    override suspend fun deleteFromTrash(id: String): Boolean = false

    override suspend fun emptyTrash(): Int? = null

    override suspend fun trashDays(): Int? = null

    override suspend fun deleteRecording(itemId: UUID): DeletedRecording = unsupported()

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

    override suspend fun recordedShows(filter: String): List<RecordingCard>? = null

    override suspend fun recordingsByDate(filter: String): DateRecordings? = null

    override suspend fun showRecordings(
        seriesId: String,
        filter: String,
    ): List<EpisodeRecording>? = null

    override suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto) = Unit

    override suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> = emptyList()

    override suspend fun favourites(): FavouritesList? = null

    override suspend fun setFavouriteEnabled(
        ruleId: String,
        enabled: Boolean,
    ): Message? = null

    override suspend fun addFavouriteByTitle(
        title: String,
        keyword: Boolean,
    ): Message? = null

    override suspend fun upcoming(): List<Upcoming>? = null

    override suspend fun schedule(): List<Upcoming>? = null

    override suspend fun jellyfinRecordings(): List<RecordingCard>? = null

    override suspend fun recentlyAdded(limit: Int): List<RecordingCard>? = null

    override suspend fun segments(itemId: UUID): List<Segment> = emptyList()

    override suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>> = false to emptyList()

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

    override suspend fun recordingOfProgram(programId: UUID): UUID? = null

    override suspend fun homeExtras(): HomeExtras? = null

    override suspend fun tuners(channelId: UUID?): TunerStatus? = null

    companion object {
        private const val CHANNEL = 0x4E50565200000001L
        private const val PROGRAM = 0x4E50565200000002L

        fun channelUuid(id: Int) = UUID(CHANNEL, id.toLong())

        fun programUuid(id: Int) = UUID(PROGRAM, id.toLong())

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
