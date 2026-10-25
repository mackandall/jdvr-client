package com.mackandall.jdvr.data

import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.HttpMethod

/**
 * Pause and rewind on live TV: the buffer recorded from tune-in. On Jellyfin this is JellyDVR
 * Live; without it buffers are empty.
 */
interface LiveBuffers {
    /** The channel's buffers, oldest first; the last is the programme on now. */
    suspend fun buffers(channelId: UUID): List<Buffer>

    /** Ask JellyDVR Live to start buffering the channel now (Pause), skipping its surfing delay. */
    suspend fun startBuffering(channelId: UUID): List<Buffer>

    /**
     * Guide programmes that are only a live TV buffer (JellyDVR Live's, not kept), in a plain
     * lower-case no-dash form: their timers look like recordings everywhere else.
     */
    suspend fun bufferPrograms(): Set<String>

    /** The buffer an item is, if it is one (for Keep in the player). */
    suspend fun bufferOfItem(itemId: UUID): Buffer?

    suspend fun keepBuffer(
        bufferId: String,
        keep: Boolean,
    ): Buffer?

    /**
     * Stop pressed: JellyDVR Live stops buffering the channel now, freeing its tuner input (it
     * leaves kept buffers, real recordings, and channels someone else is watching). Runs on its
     * own scope so it finishes even as the player closes.
     */
    fun stopBuffering(channelId: UUID)
}

@Serializable
data class Buffer(
    @SerialName("Id") val id: String,
    @SerialName("ProgramName") val programName: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("StartedUtc") val startedUtc: String? = null,
    @SerialName("Recording") val recording: Boolean = false,
    @SerialName("Kept") val kept: Boolean = false,
    @SerialName("ProgramId") val programId: String? = null,
)

@Serializable
private data class Buffers(
    @SerialName("Buffers") val buffers: List<Buffer> = emptyList(),
)

@Serializable
private data class BufferPrograms(
    @SerialName("ProgramIds") val programIds: List<String> = emptyList(),
)

@Serializable
data class Stopped(
    @SerialName("Stopped") val stopped: Int = 0,
)

/** JellyDVR Live's endpoints (/JellyDvrLive/…). */
class LivePlugin(
    private val server: PluginClient,
) : LiveBuffers {
    override suspend fun buffers(channelId: UUID): List<Buffer> =
        server.call<Buffers>(HttpMethod.GET, "/JellyDvrLive/Channel/${channelId.plain()}")?.buffers.orEmpty()

    override suspend fun startBuffering(channelId: UUID): List<Buffer> =
        server.call<Buffers>(HttpMethod.POST, "/JellyDvrLive/Channel/${channelId.plain()}/Start")?.buffers.orEmpty()

    override suspend fun bufferPrograms(): Set<String> =
        server.call<BufferPrograms>(HttpMethod.GET, "/JellyDvrLive/Programs")?.programIds.orEmpty().map { it.plainId() }.toSet()

    override suspend fun bufferOfItem(itemId: UUID): Buffer? = server.call(HttpMethod.GET, "/JellyDvrLive/Item/${itemId.plain()}")

    override suspend fun keepBuffer(
        bufferId: String,
        keep: Boolean,
    ): Buffer? = server.call(if (keep) HttpMethod.POST else HttpMethod.DELETE, "/JellyDvrLive/Keep/$bufferId")

    override fun stopBuffering(channelId: UUID) {
        kotlinx.coroutines.MainScope().launch {
            server.call<Stopped>(HttpMethod.POST, "/JellyDvrLive/Channel/${channelId.plain()}/Stop")
        }
    }
}
