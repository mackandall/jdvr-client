package com.mackandall.jdvr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.HttpMethod
import java.util.UUID

/** Whether the server can combine pictures (JellyDVR Live's server Multiview), and who's using it. */
@Serializable
data class MultiviewCapability(
    @SerialName("Available") val available: Boolean = false,
    @SerialName("Reason") val reason: String = "",
    @SerialName("Speed") val speed: Double? = null,
    @SerialName("Max") val max: Int = 1,
    @SerialName("InUse") val inUse: List<String> = emptyList(),
)

/** One picture in the server's combined frame: its channel, its sound track, and where it sits (frame pixels). */
@Serializable
data class MultiviewTileDto(
    @SerialName("ChannelId") val channelId: String,
    @SerialName("ChannelName") val channelName: String = "",
    @SerialName("AudioTrack") val audioTrack: Int = 0,
    @SerialName("X") val x: Int = 0,
    @SerialName("Y") val y: Int = 0,
    @SerialName("W") val w: Int = 0,
    @SerialName("H") val h: Int = 0,
)

/** A server Multiview: the combined stream's address and its frame. */
@Serializable
data class MultiviewSessionDto(
    @SerialName("Id") val id: String,
    @SerialName("Layout") val layout: String = "grid",
    @SerialName("Generation") val generation: Int = 0,
    @SerialName("StreamPath") val streamPath: String,
    @SerialName("Width") val width: Int = 1920,
    @SerialName("Height") val height: Int = 1080,
    @SerialName("Tiles") val tiles: List<MultiviewTileDto> = emptyList(),
)

@Serializable
private data class MultiviewRequest(
    @SerialName("ChannelIds") val channelIds: List<String>,
    @SerialName("Layout") val layout: String,
    @SerialName("DeviceName") val deviceName: String,
)

@Serializable
private data class Refusal(
    @SerialName("Message") val message: String = "",
)

/** The server said no (busy elsewhere, a channel wouldn't open): [message] is for the screen. */
class MultiviewRefused(
    message: String,
) : Exception(message)

/** Calls to JellyDVR Live's server Multiview (Jellyfin servers only). */
object MultiviewServer {
    private val json = Json { ignoreUnknownKeys = true }

    /** Debug: a stand-in server (recorded files, no tuners) instead of the Jellyfin plugin. */
    var testBase: String? = null

    private suspend fun test(
        method: String,
        path: String,
        body: String? = null,
        base: String = testBase!!,
    ): Pair<Int, String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rb = body?.toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder().url(base.trimEnd('/') + path).method(method, rb).build()
            Jdvr.http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }

    /** Null when the plugin isn't there (or is too old to have Multiview). */
    suspend fun capability(check: Boolean = false): MultiviewCapability? {
        if (testBase != null) return json.decodeFromString(MultiviewCapability.serializer(), test("GET", "/JellyDvrLive/Multiview/Capability").second)
        if (Jdvr.isNextPvr) return null
        return PluginClient { Jdvr.api ?: error("Not signed in") }.call(HttpMethod.GET, "/JellyDvrLive/Multiview/Capability", if (check) mapOf("check" to true) else emptyMap())
    }

    suspend fun start(
        channels: List<UUID>,
        layout: String,
    ): MultiviewSessionDto = send(HttpMethod.POST, "/JellyDvrLive/Multiview", channels, layout)

    suspend fun change(
        id: String,
        channels: List<UUID>,
        layout: String,
    ): MultiviewSessionDto = send(HttpMethod.POST, "/JellyDvrLive/Multiview/$id", channels, layout)

    suspend fun stop(
        id: String,
        // Captured when the stop was asked (the test base is cleared as Multiview closes).
        base: String? = testBase,
    ) {
        if (base != null) {
            test("DELETE", "/JellyDvrLive/Multiview/$id", base = base)
            return
        }
        PluginClient { Jdvr.api ?: error("Not signed in") }.bytes(HttpMethod.DELETE, "/JellyDvrLive/Multiview/$id")
    }

    /** The combined stream's full address. */
    fun streamUrl(s: MultiviewSessionDto): String = (testBase ?: Jdvr.api?.baseUrl ?: "").trimEnd('/') + s.streamPath

    private suspend fun send(
        method: HttpMethod,
        path: String,
        channels: List<UUID>,
        layout: String,
    ): MultiviewSessionDto {
        val body = json.encodeToString(MultiviewRequest.serializer(), MultiviewRequest(channels.map { it.toString() }, layout, deviceName()))
        if (testBase != null) {
            val (code, text) = test("POST", path, body)
            if (code != 200) throw MultiviewRefused("The stand-in server said $code.")
            return json.decodeFromString(MultiviewSessionDto.serializer(), text)
        }
        val api = Jdvr.api ?: error("Not signed in")
        val reply =
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.request(method = method, pathTemplate = path, requestBody = json.parseToJsonElement(body))
                }
            } catch (e: org.jellyfin.sdk.api.client.exception.InvalidStatusException) {
                // 409: busy or a channel wouldn't open (the server says which).
                throw MultiviewRefused(if (e.status == 409) "The server couldn't start Multiview: it may be busy on another TV." else "The server couldn't start Multiview (${e.status}).")
            }
        return json.decodeFromString(MultiviewSessionDto.serializer(), reply.body.decodeToString())
    }

    /** This TV's name, so the server can say which TV is using Multiview. */
    fun deviceName(): String =
        runCatching {
            android.provider.Settings.Global.getString(Jdvr.contentResolver(), "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
}
