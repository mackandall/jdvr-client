package com.mackandall.jdvr.data

import com.mackandall.jdvr.ui.day
import com.mackandall.jdvr.ui.time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.buffer
import org.jellyfin.sdk.model.api.PlayMethod
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Where a keyword rule looks: the title, the title or description, or the cast and crew. */
enum class KeywordMatch { TITLE, ANYWHERE, PEOPLE }

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

    /** Signs in, or throws why not (a wrong PIN, or no NextPVR at the address). */
    suspend fun checkSignIn() {
        session()
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

    override suspend fun channelGroups(): List<String> =
        call("channel.groups").jsonObject["groups"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    // The group picked in the guide, when this server has it (else every channel).
    override suspend fun channels(): List<Channel> {
        val group = Jdvr.prefs.channelGroup.takeIf { it.isNotEmpty() && it in runCatching { channelGroups() }.getOrDefault(emptyList()) }
        return channelList(group?.let { mapOf("group_id" to it) } ?: emptyMap())
    }

    override suspend fun allChannels(): List<Channel> = channelList(emptyMap())

    private suspend fun channelList(params: Map<String, Any>): List<Channel> {
        val list = call("channel.list", params).jsonObject["channels"]?.jsonArray.orEmpty().map { it.jsonObject }
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
        LiveHold.release()
        runCatching { call("channel.stop") }
    }

    override suspend fun warmHls(url: String) = Unit

    // NextPVR writes what the tuner sends to a buffer file from tune-in (system.status names it:
    // "LIVE&…/live-<channel name>-<n>-<tuner>.ts"); /stream?f= serves it with byte ranges, so it
    // seeks like a recording. The tuner starts writing once the player opens the stream.
    override suspend fun liveCopyUrl(
        p: LivePlayback,
        notThis: String?,
    ): String? {
        val old = notThis?.toHttpUrl()?.queryParameter("f")
        val name = runCatching { channelInfo()[nextPvrId(p.channelId)]?.str("channelName") }.getOrNull() ?: return null
        fun plain(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        repeat(20) {
            val file =
                runCatching {
                    call("system.status").jsonObject["status"]?.jsonArray.orEmpty()
                        .flatMap { d -> d.jsonObject["streams"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content } }
                        .filter { it.startsWith("LIVE&") }
                        .map { it.substringAfter('&') }
                        .filter { it != old }
                        .lastOrNull { f -> Regex("""live-(.+)-\d+-\d+\.ts$""").find(f.substringAfterLast('/'))?.groupValues?.get(1)?.let { plain(it) == plain(name) } == true }
                }.getOrNull()
            if (file != null) {
                return baseUrl.toHttpUrl().newBuilder().addPathSegment("stream")
                    .addQueryParameter("f", file)
                    .addQueryParameter("mode", "http")
                    .addQueryParameter("sid", session())
                    .build().toString()
            }
            delay(500)
        }
        return null
    }

    override val liveCopyIsFile: Boolean get() = true

    override fun liveCopyOpenUrl(url: String): String = pinnedNow(url)

    override fun holdLive(on: Boolean) = if (on) LiveHold.park() else LiveHold.release()

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
        // NextPVR keeps only where playback stopped: near the end counts as watched.
        val watched get() = seconds > 0 && positionS >= seconds * 9 / 10
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
            // The screens offer Delete (not "delete from the archive") for the live recordings folder.
            location = "Live recordings folder",
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

    /**
     * NextPVR's delete stops a recording under way (keeping the file) and deletes a finished one;
     * so one still recording is stopped first, then deleted once NextPVR has closed it.
     */
    override suspend fun deleteRecording(itemId: UUID): DeletedRecording {
        val id = nextPvrId(itemId)
        if (runCatching { recording(itemId).recordingNow }.getOrDefault(false)) {
            call("recording.delete", mapOf("recording_id" to id))
            repeat(10) {
                if (!runCatching { recording(itemId).recordingNow }.getOrDefault(false)) return@repeat
                kotlinx.coroutines.delay(500)
            }
        }
        call("recording.delete", mapOf("recording_id" to id))
        return DeletedRecording(deleted = 1)
    }

    /** A recording under way can be stopped ("Stop recording"): NextPVR keeps what it has. */
    override suspend fun activeRecordings(itemId: UUID): ActiveRecordings? {
        if (itemId.mostSignificantBits != RECORDING) return null
        val r = runCatching { recording(itemId) }.getOrNull() ?: return null
        return ActiveRecordings(canStop = true, recordings = if (r.recordingNow) listOf(ActiveRecording(id = r.uuid.toString(), name = r.name)) else emptyList())
    }

    override suspend fun stopRecording(recordingId: String): Boolean {
        val id = runCatching { UUID.fromString(recordingId) }.getOrNull() ?: return false
        call("recording.delete", mapOf("recording_id" to nextPvrId(id)))
        return true
    }

    override suspend fun deleteItem(itemId: UUID) {
        deleteRecording(itemId)
    }

    // No commercials from NextPVR yet; whether it's still recording (the player says so, and goes on).
    override suspend fun liveSegments(itemId: UUID): Pair<Boolean, List<Segment>> =
        (itemId.mostSignificantBits == RECORDING && runCatching { recording(itemId).recordingNow }.getOrDefault(false)) to emptyList()

    override suspend fun recordingOfProgram(programId: UUID): UUID? =
        recordings().firstOrNull { it.done && it.o.int("epgEventId") == nextPvrId(programId) }?.uuid

    /** Watched: the position at the end; not watched: no position (NextPVR has no flag of its own). */
    override suspend fun setPlayed(
        id: UUID,
        played: Boolean,
    ) {
        val r = recording(id)
        if (played) call("recording.watched.set", mapOf("recording_id" to r.id, "position" to r.seconds)) else call("recording.watched.clear", mapOf("recording_id" to r.id))
    }

    /**
     * Other airings of a guide entry or a recording, from NextPVR's own guide search: the same
     * episode (by its title) on any channel, or for a movie every showing of it.
     */
    override suspend fun airings(
        itemId: UUID?,
        programId: UUID?,
    ): AiringsResult? {
        val (title, episode, isMovie) =
            when {
                programId != null -> (program(programId) ?: return null).let { Triple(it.name, it.rawEpisodeTitle, !it.isSeries) }
                itemId != null && itemId.mostSignificantBits == RECORDING -> recording(itemId).let { Triple(it.name, it.episodeTitle, !it.isSeries) }
                else -> return null
            }
        val channels = runCatching { channelInfo() }.getOrDefault(emptyMap())
        val tuners = runCatching { call("system.status").jsonObject["status"]?.jsonArray?.size }.getOrNull() ?: 0
        val now = System.currentTimeMillis() / 1000
        val found =
            call("channel.listings.search", mapOf("title" to title)).jsonObject["listings"]?.jsonArray.orEmpty().map { it.jsonObject }
                .filter { it.str("name").equals(title, true) && (it.long("end") ?: 0) > now }
                .filter { episode == null || it.str("subtitle").equals(episode, true) }
                .sortedBy { it.long("start") }
        return AiringsResult(
            title = title,
            isMovie = isMovie,
            tuners = tuners,
            airings =
                found.map { o ->
                    val id = o.int("id") ?: 0
                    val ch = o.int("channelId")
                    val start = o.long("start") ?: 0
                    val end = o.long("end") ?: 0
                    Airing(
                        programId = programUuid(id).toString(),
                        name = o.str("name").orEmpty(),
                        episodeTitle = o.str("subtitle"),
                        start = Instant.ofEpochSecond(start).toString(),
                        end = Instant.ofEpochSecond(end).toString(),
                        channelId = ch?.let { channelUuid(it).toString() },
                        channelName = o.str("channelName") ?: ch?.let { channels[it]?.str("channelName") },
                        channelNumber = o.str("channelNumber") ?: ch?.let { channels[it]?.str("channelNumberFormated") },
                        hasLogo = ch?.let { channels[it]?.bool("channelIcon") } == true,
                        recording = if (o.int("recording_id") != null) "recording" else null,
                        tuners = tuners,
                        isThis = programId != null && programUuid(id) == programId,
                        onNow = start <= now && end > now,
                    )
                },
        )
    }

    // ---- series recordings ------------------------------------------------------------------

    /**
     * NextPVR's recurring rules, in the shape the Series screen shows (padding there is minutes),
     * highest priority first (NextPVR's lowest number: it wins when tuners clash).
     */
    override suspend fun seriesTimers(): List<org.jellyfin.sdk.model.api.SeriesTimerInfoDto> {
        // What each rule has scheduled: a keyword rule shows what it caught.
        val scheduled = runCatching { recordings().filter { it.status == "pending" }.groupBy { it.o.int("recurringParent") ?: 0 } }.getOrDefault(emptyMap())
        return call("recording.recurring.list").jsonObject["recurrings"]?.jsonArray.orEmpty().map { it.jsonObject }.sortedBy { it.int("priority") ?: Int.MAX_VALUE }.map { r ->
            val period = r.str("period").orEmpty().replace('\u202f', ' ')
            val type = r.int("type") ?: 2
            val kind = ruleKind(r)
            val what =
                when {
                    kind == "keyword" -> keywordOf(r.str("advancedRules").orEmpty())?.let { (k, m) -> "“$k” " + when (m) {
                        KeywordMatch.TITLE -> "in the title"
                        KeywordMatch.ANYWHERE -> "in the title or description"
                        KeywordMatch.PEOPLE -> "in the cast or crew"
                    } } ?: "rule: ${r.str("advancedRules").orEmpty()}"
                    // Types 3–6 record one timeslot: NextPVR words it "2:30 AM - 3:00 AM (Daily)".
                    type in 3..6 -> period
                    period.contains("new", true) -> "new episodes only"
                    else -> "all episodes"
                }
            val mine = scheduled[r.int("id")].orEmpty().sortedBy { it.start }
            // The channel only when the rule records on any.
            val next = mine.firstOrNull()?.let { "next ${time(it.start).day()} ${time(it.start).time()}" + (it.o.str("channel")?.takeIf { (r.int("channelID") ?: 0) == 0 }?.let { c -> " on $c" } ?: "") }
            org.jellyfin.sdk.model.api.SeriesTimerInfoDto(
                id = r.int("id")?.toString(),
                // Switched off in its options: still listed, records nothing.
                name = r.str("name") + if (r.bool("enabled") == false) " (switched off)" else "",
                channelName = r.str("channel")?.takeIf { (r.int("channelID") ?: 0) != 0 },
                recordAnyChannel = (r.int("channelID") ?: 0) == 0 || period.contains("all channels", true),
                // Types 3–6 record one timeslot (daily, weekly, weekdays, weekends).
                recordAnyTime = (r.int("type") ?: 2) !in 3..6,
                // Which airings, then what's scheduled ("3 scheduled · next Mon 3:00 PM KFOR-DT").
                overview = listOfNotNull(what, if (mine.isEmpty()) "nothing scheduled" else "${mine.size} scheduled", next).joinToString(" · "),
                // "series", "keyword" or "manual": which options its Edit opens.
                serviceName = kind,
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
    }

    /** "keyword" (an advanced rule), "manual" (a timeslot set by hand, not from the guide) or "series". */
    private fun ruleKind(r: JsonObject): String =
        when {
            r.int("type") == 8 || r.str("advancedRules") != null -> "keyword"
            (r.int("type") ?: 2) in 3..6 && r.str("epgTitle").isNullOrEmpty() -> "manual"
            else -> "series"
        }

    override suspend fun updateSeriesTimer(t: org.jellyfin.sdk.model.api.SeriesTimerInfoDto): Unit = error("NextPVR's series are changed with its own options")

    /**
     * A recurring rule's options, as NextPVR's web page's Advanced record sets them. [type] is
     * NextPVR's: 1 new episodes, 2 all episodes, 3 daily / 4 weekly / 5 weekdays / 6 weekends in
     * this timeslot, 7 all episodes on all channels. Padding is minutes; keep 0 = all.
     */
    data class SeriesRule(
        // Null for a new rule, made from [eventId].
        val id: Int?,
        val eventId: Int?,
        val name: String,
        // 0: all channels.
        val channelId: Int,
        val channelName: String?,
        val type: Int,
        val keep: Int,
        val prePadding: Int,
        val postPadding: Int,
        // Null: the server's default folder.
        val directory: String?,
        val enabled: Boolean,
        // The airing it was set up from: the timeslot of types 3–6.
        val start: LocalDateTime?,
    )

    /** Moves a series one place up ([higher]) or down the priority list: it swaps with its neighbour. */
    suspend fun moveSeries(
        id: String,
        higher: Boolean,
    ) {
        call("recording.recurring.priority", mapOf("recurring_id" to id, "direction" to if (higher) "higher" else "lower"))
    }

    /** The server's default padding (minutes) and its recording folders ("Default" first). */
    class SeriesDefaults(val prePadding: Int, val postPadding: Int, val directories: List<String>)

    suspend fun seriesDefaults(): SeriesDefaults {
        val o = call("setting.list").jsonObject
        return SeriesDefaults(
            prePadding = o.str("prePadding")?.toIntOrNull() ?: 0,
            postPadding = o.str("postPadding")?.toIntOrNull() ?: 0,
            directories = o.str("recordingDirectories").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Default") },
        )
    }

    /** What Record series starts from: all episodes on this channel, as NextPVR's own default. */
    fun newSeriesRule(
        programId: UUID,
        name: String,
        channelId: UUID?,
        channelName: String?,
        start: LocalDateTime?,
        defaults: SeriesDefaults,
    ) = SeriesRule(
        id = null,
        eventId = nextPvrId(programId),
        name = name,
        channelId = channelId?.let { nextPvrId(it) } ?: 0,
        channelName = channelName,
        type = 2,
        keep = 0,
        prePadding = defaults.prePadding,
        postPadding = defaults.postPadding,
        directory = null,
        enabled = true,
        start = start,
    )

    suspend fun seriesRule(id: String): SeriesRule? =
        call("recording.recurring.list").jsonObject["recurrings"]?.jsonArray.orEmpty().map { it.jsonObject }
            .firstOrNull { it.int("id")?.toString() == id }?.let { r ->
                SeriesRule(
                    id = r.int("id"),
                    eventId = null,
                    name = r.str("name").orEmpty(),
                    channelId = r.int("channelID") ?: 0,
                    channelName = r.str("channel")?.takeIf { (r.int("channelID") ?: 0) != 0 },
                    type = r.int("type") ?: 2,
                    keep = r.int("keep") ?: 0,
                    prePadding = r.int("prePadding") ?: 0,
                    postPadding = r.int("postPadding") ?: 0,
                    directory = r.str("directoryID")?.trim('[', ']')?.takeIf { it.isNotEmpty() },
                    enabled = r.bool("enabled") ?: true,
                    start = r.long("startTimeTicks")?.let { time(it) },
                )
            }

    /**
     * Saves a rule: a new one from its guide entry, an existing one in place. Padding is always
     * sent (left out, an edit keeps the old value). A rule on all channels has lost its channel,
     * so going back to one channel takes the channel of its next airing in the guide.
     */
    suspend fun saveSeriesRule(r: SeriesRule) {
        val params = mutableMapOf<String, Any?>(
            "recurring_type" to r.type,
            "keep" to r.keep,
            "pre_padding" to r.prePadding,
            "post_padding" to r.postPadding,
            "directory_id" to r.directory,
        )
        if (r.id == null) {
            params["event_id"] = r.eventId
        } else {
            params["recurring_id"] = r.id
            params["enabled"] = r.enabled
            if (r.type != 7) {
                val channel = r.channelId.takeIf { it != 0 }
                    ?: call("channel.listings.search", mapOf("title" to r.name)).jsonObject["listings"]?.jsonArray.orEmpty().map { it.jsonObject }
                        .filter { it.str("name").equals(r.name, true) && (it.long("end") ?: 0) > Instant.now().epochSecond }
                        .minByOrNull { it.long("start") ?: 0 }?.int("channelId")
                    ?: error("${r.name} isn't in the guide: no channel to record it on")
                params["channel_id"] = channel
            }
        }
        call("recording.recurring.save", params)
    }

    // ---- keyword rules and manual recordings --------------------------------------------------

    /** Channels to pick from for a rule: id and "4.1 KFOR-DT", in the server's order (every group). */
    suspend fun ruleChannels(): List<Pair<Int, String>> =
        call("channel.list").jsonObject["channels"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { c ->
            val id = c.int("channelId") ?: return@mapNotNull null
            id to listOfNotNull(c.str("channelNumberFormated"), c.str("channelName")).joinToString(" ")
        }

    /**
     * A keyword rule: records whatever matches [keyword] (NextPVR's web page's Keyword type, which
     * it keeps as an advanced rule). [custom] is an advanced rule written some other way, kept as
     * it is. [channelId] 0: any channel.
     */
    data class KeywordRule(
        val id: Int?,
        val name: String,
        val keyword: String,
        val match: KeywordMatch,
        val custom: String?,
        val channelId: Int,
        val keep: Int,
        val prePadding: Int,
        val postPadding: Int,
        val directory: String?,
        val enabled: Boolean,
    )

    fun newKeywordRule(
        keyword: String,
        defaults: SeriesDefaults,
    ) = KeywordRule(null, "", keyword, KeywordMatch.TITLE, null, 0, 0, defaults.prePadding, defaults.postPadding, null, true)

    suspend fun keywordRule(id: String): KeywordRule? =
        rule(id)?.let { r ->
            val rules = r.str("advancedRules").orEmpty()
            val parsed = keywordOf(rules)
            KeywordRule(
                id = r.int("id"),
                name = r.str("name").orEmpty(),
                keyword = parsed?.first.orEmpty(),
                match = parsed?.second ?: KeywordMatch.TITLE,
                custom = rules.takeIf { parsed == null },
                channelId = r.int("channelID") ?: 0,
                keep = r.int("keep") ?: 0,
                prePadding = r.int("prePadding") ?: 0,
                postPadding = r.int("postPadding") ?: 0,
                directory = r.str("directoryID")?.trim('[', ']')?.takeIf { it.isNotEmpty() },
                enabled = r.bool("enabled") ?: true,
            )
        }

    /** Saves a keyword rule, new or in place; NextPVR schedules what it matches straight away. */
    suspend fun saveKeywordRule(r: KeywordRule) {
        val params = mutableMapOf<String, Any?>(
            "recurring_type" to "advanced",
            "name" to r.name.ifBlank { r.keyword.ifBlank { r.custom.orEmpty() } },
            "channel" to r.channelId,
            "advanced" to (r.custom ?: keywordQuery(r.keyword, r.match)),
            "keep" to r.keep,
            "pre_padding" to r.prePadding,
            "post_padding" to r.postPadding,
            "directory_id" to r.directory,
        )
        if (r.id != null) {
            params["recurring_id"] = r.id
            params["enabled"] = r.enabled
        }
        call("recording.recurring.save", params)
    }

    /**
     * A recording by channel and time rather than from the guide: [repeat] 0 once, or NextPVR's
     * timeslot types (3 daily, 4 weekly, 5 Monday to Friday, 6 weekends) from [start] on.
     */
    data class ManualRule(
        val id: Int?,
        val name: String,
        val channelId: Int,
        val repeat: Int,
        val start: LocalDateTime,
        val minutes: Int,
        val keep: Int,
        val prePadding: Int,
        val postPadding: Int,
        val directory: String?,
        val enabled: Boolean,
    )

    /** A new manual recording: on [channelId] for [start]–[end] when opened from the guide, else the next half hour. */
    fun newManualRule(
        channelId: UUID?,
        start: LocalDateTime?,
        end: LocalDateTime?,
        defaults: SeriesDefaults,
    ): ManualRule {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val from = start?.takeIf { it.isAfter(now) } ?: now.plusMinutes((30 - now.minute % 30).toLong())
        val minutes = if (start != null && end != null) java.time.Duration.between(start, end).toMinutes().toInt().coerceIn(5, 600) else 60
        return ManualRule(null, "", channelId?.let { nextPvrId(it) } ?: 0, 0, from, minutes, 0, defaults.prePadding, defaults.postPadding, null, true)
    }

    suspend fun manualRule(id: String): ManualRule? =
        rule(id)?.let { r ->
            val start = r.long("startTimeTicks") ?: return null
            ManualRule(
                id = r.int("id"),
                name = r.str("name").orEmpty(),
                channelId = r.int("channelID") ?: 0,
                repeat = r.int("type") ?: 3,
                start = time(start),
                minutes = (((r.long("endTimeTicks") ?: start) - start) / 60).toInt().coerceAtLeast(5),
                keep = r.int("keep") ?: 0,
                prePadding = r.int("prePadding") ?: 0,
                postPadding = r.int("postPadding") ?: 0,
                directory = r.str("directoryID")?.trim('[', ']')?.takeIf { it.isNotEmpty() },
                enabled = r.bool("enabled") ?: true,
            )
        }

    /** Saves a manual recording: once (recording.save) or as a timeslot rule, new or in place. */
    suspend fun saveManualRule(
        r: ManualRule,
        channelName: String,
    ) {
        val start = r.start.atZone(zone).toEpochSecond()
        val name = r.name.ifBlank { "$channelName ${r.start.time()}" }
        val padding = mapOf("pre_padding" to r.prePadding, "post_padding" to r.postPadding, "directory_id" to r.directory)
        if (r.repeat == 0) {
            call("recording.save", mapOf("name" to name, "channel" to r.channelId, "time_t" to start, "duration" to r.minutes * 60) + padding)
            return
        }
        val params = mutableMapOf<String, Any?>(
            "recurring_type" to r.repeat,
            "name" to name,
            "channel_id" to r.channelId,
            "start_time" to start,
            "end_time" to start + r.minutes * 60,
            "keep" to r.keep,
        )
        params += padding
        if (r.id != null) {
            params["recurring_id"] = r.id
            params["enabled"] = r.enabled
        }
        call("recording.recurring.save", params)
    }

    private suspend fun rule(id: String): JsonObject? =
        call("recording.recurring.list").jsonObject["recurrings"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { it.int("id")?.toString() == id }

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

    override suspend fun markSymbols(): MarkSymbols? = null

    override suspend fun hasEpisodeHistory(): Boolean = false

    override suspend fun scheduleState(programId: UUID): ScheduleState? = null

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

    /**
     * Commercial breaks from the .edl beside a recording (NextPVR's own Comskip, or one run by
     * hand), via recording.edl; null when there's none. Not [call]: "not found" isn't a lapsed
     * session, and signing in again for every unscanned recording would be slow.
     */
    private suspend fun edl(itemId: UUID): List<Segment>? {
        if (itemId.mostSignificantBits != RECORDING) return null
        val reply =
            runCatching { withContext(Dispatchers.IO) { get(serviceUrl("recording.edl", mapOf("recording_id" to nextPvrId(itemId)), session())) } }
                .getOrNull() as? JsonObject ?: return null
        val breaks = reply["commercials"]?.jsonArray ?: return null
        return breaks.mapNotNull { b ->
            val o = b.jsonObject
            val start = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = o["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Segment(type = "Commercial", startMs = (start * 1000).toLong(), endMs = (end * 1000).toLong())
        }
    }

    @Volatile private var scannerAt = 0L

    @Volatile private var scannerItems: Map<String, JsonObject>? = null

    private val scannerHttp by lazy {
        Jdvr.http.newBuilder()
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * The Comskip scanner beside NextPVR (tools/nextpvr-comskip, port 8867), by recording file:
     * what it's scanning or gave up on. Asked every 10 s at most; with no scanner there, every 5 min.
     */
    private suspend fun scanner(): Map<String, JsonObject>? {
        val now = System.currentTimeMillis()
        if (scannerAt != 0L && now - scannerAt < (if (scannerItems == null) 300_000 else 10_000)) return scannerItems
        scannerAt = now
        val url = baseUrl.toHttpUrl().newBuilder().port(8867).encodedPath("/api/status").query(null).build()
        scannerItems =
            runCatching {
                withContext(Dispatchers.IO) {
                    scannerHttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("scanner answered ${r.code}")
                        json.parseToJsonElement(r.body.string()).jsonObject["items"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject }
                    }
                }
            }.getOrNull()
        return scannerItems
    }

    // A badge for recordings the scanner is on or gave up on, then for those with an .edl (never
    // scanned: none, as on Jellyfin).
    override suspend fun commercialBadges(ids: List<UUID>): Map<String, CommercialBadge> =
        coroutineScope {
            val wanted = ids.filter { it.mostSignificantBits == RECORDING }
            val items = scanner().orEmpty()
            val busy = items.filterValues { it.str("state") in setOf("scanning", "queued", "failed") }
            // Marks the user removed: no badge, rather than "no ads".
            val cleared = items.filterValues { it["cleared"]?.jsonPrimitive?.booleanOrNull == true && it.str("state") == "none" }.keys
            val files = if (busy.isEmpty() && cleared.isEmpty()) emptyMap() else recordings().associate { it.uuid to it.o.str("file") }
            // Waiting to be scanned again shows as scanning.
            val scanning = wanted.mapNotNull { id -> busy[files[id]]?.str("state")?.let { id to CommercialBadge(state = if (it == "queued") "scanning" else it) } }.toMap()
            val marked =
                (wanted - scanning.keys).filter { files[it] !in cleared }.chunked(8).flatMap { chunk ->
                    chunk.map { id -> async { edl(id)?.let { id to it } } }.awaitAll().filterNotNull()
                }.associate { (id, breaks) ->
                    val minutes = ((breaks.sumOf { it.endMs - it.startMs } + 30_000) / 60_000).toInt()
                    id to CommercialBadge(state = if (breaks.isEmpty()) "none" else "found", minutes = minutes)
                }
            (scanning + marked).mapKeys { it.key.toString().plainId() }
        }

    // A show: "scanning" while one of its recordings is, else how many are scanned (! when one failed).
    override suspend fun showCommercialBadges(showIds: List<String>): Map<String, CommercialBadge> {
        val wanted = showIds.toSet()
        val shows = recordings().filter { it.done && it.isSeries && showId(it.name) in wanted }.groupBy { showId(it.name) }
        if (shows.isEmpty()) return emptyMap()
        val each = commercialBadges(shows.values.flatten().map { it.uuid })
        return shows.mapNotNull { (show, recs) ->
            val states = recs.mapNotNull { each[it.uuid.toString().plainId()]?.state }
            val badge =
                when {
                    "scanning" in states -> CommercialBadge(state = "scanning")
                    states.isEmpty() -> return@mapNotNull null
                    else -> CommercialBadge(state = "summary", done = states.count { it == "found" || it == "none" }, total = recs.size, failed = "failed" in states)
                }
            show.plainId() to badge
        }.toMap()
    }

    private fun scannerUrl(
        path: String,
        file: String? = null,
    ) = baseUrl.toHttpUrl().newBuilder().port(8867).encodedPath(path).query(null).apply { file?.let { addQueryParameter("file", it) } }.build()

    private fun scannerGet(
        path: String,
        file: String? = null,
    ): JsonObject =
        scannerHttp.newCall(okhttp3.Request.Builder().url(scannerUrl(path, file)).build()).execute().use { r ->
            if (!r.isSuccessful) error("the scanner answered ${r.code}")
            json.parseToJsonElement(r.body.string()).jsonObject
        }

    // The scanner's token for changes. It unlocks the way NextPVR's own sign-in works, with the
    // PIN's md5 this device already keeps: nobody types anything.
    @Volatile private var scannerToken: String? = null

    private fun unlockScanner(): String {
        val c = scannerGet("/api/unlock")
        val body = JsonObject(mapOf("challenge" to JsonPrimitive(c.str("challenge")), "md5" to JsonPrimitive(md5(":$pinMd5:${c.str("salt")}"))))
        val reply = scannerSend("/api/unlock", body, null)
        return reply.second.str("token") ?: error(reply.second.str("error") ?: "the scanner wouldn't unlock")
    }

    private fun scannerSend(
        path: String,
        body: JsonObject,
        token: String?,
    ): Pair<Int, JsonObject> {
        val req =
            okhttp3.Request.Builder().url(scannerUrl(path))
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), body.toString()))
                .apply { token?.let { header("X-Token", it) } }
                .build()
        return scannerHttp.newCall(req).execute().use { r -> r.code to (runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())) }
    }

    /** A change on the scanner, unlocking first (or again, when its token has run out). */
    private suspend fun scannerPost(
        path: String,
        file: String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val body = JsonObject(mapOf("file" to JsonPrimitive(file)))
            var token = scannerToken ?: unlockScanner().also { scannerToken = it }
            var reply = scannerSend(path, body, token)
            if (reply.first == 403) {
                token = unlockScanner().also { scannerToken = it }
                reply = scannerSend(path, body, token)
            }
            // Its next status is asked for straight away.
            scannerAt = 0L
            if (reply.first !in 200..299) error(reply.second.str("error") ?: "the scanner answered ${reply.first}")
            reply.second
        }

    // A finished recording's file, when the scanner is there to act on it.
    private suspend fun scannerFile(itemId: UUID): String? {
        if (itemId.mostSignificantBits != RECORDING || scanner() == null) return null
        return runCatching { recording(itemId) }.getOrNull()?.takeIf { it.status == "ready" }?.o?.str("file")
    }

    /**
     * Where the scanner is with a recording, for its menu: scanning (or waiting), found breaks
     * (Remove commercial marks), or none / failed / not scanned (Scan for commercials). Null, so
     * no buttons, without the scanner, or when the marks came from another tool.
     */
    override suspend fun commercialStatus(itemId: UUID): CommercialStatus? {
        val file = scannerFile(itemId) ?: return null
        val r = runCatching { withContext(Dispatchers.IO) { scannerGet("/api/recording", file) } }.getOrNull() ?: return null
        // The badges' copy of the scanner's states is older than this: read it again next time.
        if (scannerItems?.get(file)?.str("state") != r.str("state")) scannerAt = 0
        if (r.bool("recording") != true || (r.bool("ours") != true && r.bool("edl") == true)) return null
        return when (val state = r.str("state")) {
            "scanning", "queued" -> CommercialStatus(inFlight = true, status = "Scanning")
            "found" -> CommercialStatus(status = "Detected", breakCount = r.int("breaks"))
            else -> CommercialStatus(status = state ?: "NotScanned", breakCount = 0)
        }
    }

    override suspend fun scanCommercials(itemId: UUID): Message? {
        val file = scannerFile(itemId) ?: return Message("The Comskip scanner isn't answering.")
        val r = scannerPost("/api/rescan", file)
        return Message(if ((r.int("queued") ?: 0) > 0) "Scanning again: new marks in a minute or two." else "Not rescanned: " + (r.str("reason") ?: "the scanner said no."))
    }

    override suspend fun removeCommercials(itemId: UUID): Cleared? {
        val file = scannerFile(itemId) ?: return Cleared(message = "The Comskip scanner isn't answering.")
        return try {
            scannerPost("/api/clear", file)
            Cleared(true, "Commercial marks removed. The scanner leaves it alone until you scan it again.")
        } catch (e: IllegalStateException) {
            Cleared(false, e.message ?: "Couldn't remove them.")
        }
    }

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

    override suspend fun segments(itemId: UUID): List<Segment> = edl(itemId).orEmpty()

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

    // Home: the default recording folder's drive (system.space), and recordings that failed this past week.
    override suspend fun homeExtras(): HomeExtras? {
        val space = runCatching { call("system.space").jsonArray.firstOrNull()?.jsonObject }.getOrNull()
        val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        val failed =
            runCatching {
                call("recording.list", mapOf("filter" to "failed")).jsonObject["recordings"]?.jsonArray.orEmpty().map { it.jsonObject }
                    .filter { (it.long("startTime") ?: 0) > weekAgo }
                    .sortedByDescending { it.long("startTime") }
                    .map { o ->
                        val at = LocalDateTime.ofInstant(Instant.ofEpochSecond(o.long("startTime") ?: 0), ZoneId.systemDefault())
                        Attention(
                            kind = "failed",
                            title = listOfNotNull(o.str("name"), o.str("subtitle")?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            detail = listOfNotNull("Failed", at.day() + " " + at.time(), o.str("channel")).joinToString(" · "),
                        )
                    }
            }.getOrDefault(emptyList())
        return HomeExtras(
            attention = failed,
            drive = space?.let { Drive(freeBytes = it.long("free") ?: 0, totalBytes = it.long("total") ?: 0) },
        )
    }

    // What each tuner is doing (system.status): a stream is "LIVE&<buffer file>" for live TV, else a
    // recording's file. Live buffers are named live-<channel name>-<n>-<tuner>.ts.
    override suspend fun tuners(channelId: UUID?): TunerStatus? {
        val devices = runCatching { call("system.status").jsonObject["status"]?.jsonArray.orEmpty().map { it.jsonObject } }.getOrNull() ?: return null
        if (devices.isEmpty()) return null
        val streams = devices.flatMap { d -> d["streams"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content } }
        val channels = if (streams.isEmpty()) emptyMap() else runCatching { channelInfo() }.getOrDefault(emptyMap())
        val recordingNow =
            if (streams.none { !it.startsWith("LIVE&") }) {
                emptyList()
            } else {
                runCatching { call("recording.list", mapOf("filter" to "recording")).jsonObject["recordings"]?.jsonArray.orEmpty().map { it.jsonObject } }.getOrDefault(emptyList())
            }
        fun plain(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        val busy =
            streams.map { st ->
                val live = st.startsWith("LIVE&")
                val file = st.substringAfter('&')
                if (live) {
                    val name = Regex("""live-(.+)-\d+-\d+\.ts$""").find(file.substringAfterLast('/'))?.groupValues?.get(1).orEmpty()
                    val ch = channels.entries.firstOrNull { plain(it.value.str("channelName").orEmpty()) == plain(name) }
                    BusyInput(
                        channelId = ch?.let { channelUuid(it.key).toString() } ?: "",
                        channelName = ch?.value?.str("channelName") ?: name.ifEmpty { "Live TV" },
                        number = ch?.value?.str("channelNumberFormated") ?: "",
                    )
                } else {
                    val r = recordingNow.firstOrNull { it.str("file") == file }
                    val ch = r?.int("channelId")
                    BusyInput(
                        channelId = ch?.let { channelUuid(it).toString() } ?: "",
                        channelName = r?.str("channel") ?: "",
                        number = ch?.let { channels[it]?.str("channelNumberFormated") } ?: "",
                        recording = listOf(r?.str("name") ?: file.substringAfterLast('/').substringBeforeLast('.')),
                    )
                }
            }
        return TunerStatus(capacity = devices.size, full = busy.size >= devices.size, busy = busy)
    }

    /**
     * The live stream (/live?channeloid=) kept open while the player is on the buffer file: once
     * [park]ed, the player closing it leaves it open instead, read a little every second (an idle
     * connection is dropped), until [release]. Another /live would start a fresh buffer.
     */
    object LiveHold {
        @Volatile private var armed = false

        @Volatile private var held: Thread? = null

        fun park() {
            armed = true
        }

        fun release() {
            armed = false
            held?.interrupt()
            held = null
        }

        // Its own client: the player cancels its call when it lets the stream go, and a cancel
        // here would close the connection being kept.
        private val keeper by lazy { okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build() }

        val interceptor =
            okhttp3.Interceptor { chain ->
                val url = chain.request().url
                if (url.encodedPath != "/live" || url.queryParameter("channeloid") == null) return@Interceptor chain.proceed(chain.request())
                val response = keeper.newCall(chain.request()).execute()
                val body = response.body
                val raw = body.source()
                val source =
                    object : okio.ForwardingSource(raw) {
                        // Closed more than once (the stream, then the body): once kept, it stays kept.
                        var kept = false

                        override fun close() {
                            if (kept) return
                            if (!armed) return super.close()
                            armed = false
                            kept = true
                            held?.interrupt()
                            held =
                                Thread {
                                    val sink = okio.Buffer()
                                    try {
                                        while (!Thread.currentThread().isInterrupted) {
                                            // At the stream's own pace: NextPVR renews the tuner's
                                            // lease only while it can write, so a slow reader lets it lapse.
                                            if (raw.read(sink, 65_536) < 0) break
                                            sink.clear()
                                        }
                                    } catch (_: InterruptedException) {
                                    } catch (e: java.io.IOException) {
                                        timber.log.Timber.w(e, "NextPVR live stream kept open: dropped")
                                    } finally {
                                        timber.log.Timber.i("NextPVR live stream kept open: let go")
                                        runCatching { raw.close() }
                                    }
                                }.apply {
                                    timber.log.Timber.i("NextPVR live stream kept open while on the buffer file")
                                    name = "nextpvr-live-hold"
                                    isDaemon = true
                                    start()
                                }
                        }
                    }.buffer()
                response.newBuilder().body(
                    object : okhttp3.ResponseBody() {
                        override fun contentType() = body.contentType()

                        override fun contentLength() = body.contentLength()

                        override fun source() = source
                    },
                ).build()
            }
    }

    companion object {
        // The URL parameter carrying a still-recording file's size when playback opened.
        private const val SNAPSHOT = "jdvrlen"

        /**
         * Makes a growing file look as long as it was when playback opened (the [SNAPSHOT] in its
         * URL): replies say that length, and stop there. Other requests pass untouched.
         */
        private val autoLengths = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** [url] (a live buffer file) pinned to the size it has when first asked for: seeks like a recording. */
        fun pinnedNow(url: String): String =
            url.toHttpUrl().newBuilder().setQueryParameter(SNAPSHOT, "auto" + System.nanoTime()).build().toString()

        val snapshotLength =
            okhttp3.Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val mark = chain.request().url.queryParameter(SNAPSHOT) ?: return@Interceptor response
                // "auto…" (a live buffer file): the size its first reply gives, for every request with that mark.
                val len =
                    if (mark.startsWith("auto")) {
                        autoLengths.getOrPut(mark) {
                            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                ?: response.body.contentLength().takeIf { response.code == 200 && it > 0 }
                                ?: return@Interceptor response
                        }
                    } else {
                        mark.toLongOrNull() ?: return@Interceptor response
                    }
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

        /** The advanced rule NextPVR's web page writes for a keyword (a ' doubled, as in SQL). */
        fun keywordQuery(
            keyword: String,
            match: KeywordMatch,
        ): String {
            val k = keyword.trim().replace("'", "''")
            return when (match) {
                KeywordMatch.TITLE -> "title like '%$k%'"
                KeywordMatch.ANYWHERE -> "(title like '%$k%' or description like '%$k%')"
                KeywordMatch.PEOPLE -> "(cast_member like '%$k%' or crew like '%$k%')"
            }
        }

        /**
         * The keyword and match of an advanced rule written as [keywordQuery] writes them, or as
         * Kodi's add-on does ("KEYWORD: x": NextPVR searches the description too); null for any
         * other rule.
         */
        fun keywordOf(rules: String): Pair<String, KeywordMatch>? {
            val r = rules.trim()
            if (r.startsWith("KEYWORD:", ignoreCase = true)) return r.substringAfter(':').trim() to KeywordMatch.ANYWHERE
            val k = Regex("""^\(?(?:title|cast_member) like '%(.*?)%'""").find(r)?.groupValues?.get(1) ?: return null
            val keyword = k.replace("''", "'")
            return KeywordMatch.entries.firstOrNull { keywordQuery(keyword, it) == r }?.let { keyword to it }
        }

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

        /**
         * NextPVR servers on the home network: every address in this device's /24 with port 8866
         * open (a short connect each, many at once), then asked whether it's NextPVR. NextPVR has a
         * discovery broadcast, but it isn't documented; a sweep of 254 addresses takes a second or two.
         */
        suspend fun findOnNetwork(): List<String> =
            withContext(Dispatchers.IO) {
                val prefixes =
                    runCatching {
                        java.net.NetworkInterface.getNetworkInterfaces().toList()
                            .filter { it.isUp && !it.isLoopback }
                            .flatMap { it.inetAddresses.toList() }
                            .filterIsInstance<java.net.Inet4Address>()
                            .filter { it.isSiteLocalAddress }
                            .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
                            .distinct()
                    }.getOrDefault(emptyList())
                val io = Dispatchers.IO.limitedParallelism(64)
                prefixes.flatMap { prefix -> (1..254).map { "$prefix.$it" } }
                    .map { host ->
                        async(io) {
                            val open =
                                runCatching {
                                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 8866), 300) }
                                    true
                                }.getOrDefault(false)
                            "http://$host:8866".takeIf { open && answers(it) }
                        }
                    }.awaitAll().filterNotNull()
            }
    }
}
