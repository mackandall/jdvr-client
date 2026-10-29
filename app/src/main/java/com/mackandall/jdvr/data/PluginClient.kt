package com.mackandall.jdvr.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import timber.log.Timber

/**
 * Calls to our server plugins (Episode History, Comskip, JellyDVR Space, Schedule and Live), made
 * through Jellyfin's API as the signed-in user. A call answers null when its plugin is missing or
 * the call fails (logged), and the screens hide what isn't there.
 */
class PluginClient(
    private val apiOf: () -> ApiClient,
) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    /** The reply read as [T], or null. */
    suspend inline fun <reified T> call(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
    ): T? {
        val reply = bytes(method, path, query, body) ?: return null
        return try {
            json.decodeFromString<T>(reply.decodeToString())
        } catch (e: IllegalArgumentException) {
            Timber.d(e, "Plugin reply from %s not understood", path)
            null
        }
    }

    /** The raw reply, or null when the call failed. */
    suspend fun bytes(
        method: HttpMethod,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
    ): ByteArray? =
        try {
            // As a JSON element: the SDK sends a plain String as text/plain, which the plugins' [FromBody] refuses (415).
            val json = body?.let { json.parseToJsonElement(it) }
            withContext(Dispatchers.IO) { apiOf().request(method = method, pathTemplate = path, queryParameters = query, requestBody = json) }.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Plugin call %s failed", path)
            null
        }
}

/** A plugin's plain answer to an action ("Recording Lost moved to the archive."). */
@Serializable
data class Message(
    @SerialName("Message") val message: String = "",
)
