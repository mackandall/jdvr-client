package com.mackandall.jdvr.data

import android.content.Context
import com.mackandall.jdvr.BuildConfig
import com.mackandall.jdvr.profile.MediaCodecCapabilitiesTest
import com.mackandall.jdvr.profile.createDeviceProfile
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.DeviceProfile
import java.util.UUID

/**
 * The app's few long-lived objects: the Jellyfin SDK, the signed-in API client, the device profile
 * and the Live TV repository. No DI framework: there are only a handful.
 */
object Jdvr {
    lateinit var session: Session
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var jellyfin: Jellyfin
        private set
    lateinit var http: OkHttpClient
        private set
    private lateinit var appContext: Context

    var api: ApiClient? = null
        private set

    val userId: UUID? get() = session.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    // Made afresh for each server (it keeps a few answers).
    private var jellyfinBackendMade: JellyfinBackend? = null
    private val jellyfinBackend: JellyfinBackend get() = jellyfinBackendMade ?: JellyfinBackend().also { jellyfinBackendMade = it }
    private var nextPvr: NextPvrBackend? = null

    /** The signed-in server's calls: Jellyfin's, or NextPVR's. */
    val backend: TvBackend get() = nextPvr ?: jellyfinBackend

    val isNextPvr: Boolean get() = nextPvr != null

    /** NextPVR's own calls (series options), when signed in to one. */
    val nextPvrBackend: NextPvrBackend? get() = nextPvr

    fun init(context: Context) {
        appContext = context.applicationContext
        session = Session(appContext)
        prefs = Prefs(appContext)
        http =
            OkHttpClient
                .Builder()
                .addInterceptor(NextPvrBackend.snapshotLength)
                .addInterceptor(NextPvrBackend.LiveHold.interceptor)
                .build()
        val factory = OkHttpFactory(http)
        jellyfin =
            createJellyfin {
                this.context = appContext
                clientInfo = ClientInfo(name = "JDVR", version = BuildConfig.VERSION_NAME)
                deviceInfo = androidDevice(appContext)
                apiClientFactory = factory
                socketConnectionFactory = factory
            }
        val url = session.serverUrl
        val token = session.accessToken
        if (url != null && token != null) {
            if (session.backend == "nextpvr") nextPvr = NextPvrBackend(url, token) else api = jellyfin.createApi(baseUrl = url, accessToken = token)
            // Signed in before servers were remembered: this one is the first on the list.
            if (session.servers.none { isCurrent(it) }) {
                val name = if (session.backend == "nextpvr") url.substringAfter("://").substringBefore(':') else url
                session.servers = listOf(SavedServer(session.backend ?: "jellyfin", url, name, session.userId, session.userName, token)) + session.servers
            }
        }
    }

    val signedIn: Boolean get() = nextPvr != null || (api != null && userId != null)

    fun signIn(
        url: String,
        userId: UUID,
        userName: String?,
        token: String,
        serverName: String? = null,
    ) {
        apply(SavedServer("jellyfin", url, serverName ?: url, userId.toString(), userName, token))
    }

    /** A NextPVR server: kept is the md5 of its PIN (encrypted, like a token), to sign in again. */
    fun signInNextPvr(
        url: String,
        pinMd5: String,
    ) {
        apply(SavedServer("nextpvr", url, url.substringAfter("://").substringBefore(':'), null, null, pinMd5))
    }

    /** The servers signed in to before, the current one first. */
    val servers: List<SavedServer> get() = session.servers

    /** The server and user signed in to now: screens and caches start afresh when it changes. */
    val serverKey: String get() = "${session.backend}|${session.serverUrl}|${session.userId}"

    fun isCurrent(s: SavedServer) = s.backend == (session.backend ?: "jellyfin") && s.url == session.serverUrl && s.userId == session.userId

    /** Back to a remembered server, signed in with what was kept. */
    fun switchTo(s: SavedServer) {
        if (s.token != null) apply(s)
    }

    /** Takes a server off the list (signing in there again asks for the PIN or a code). */
    fun forget(s: SavedServer) {
        session.servers = session.servers.filterNot { it.sameAs(s) }
    }

    private fun apply(s: SavedServer) {
        // The one being left keeps its last and recent channels for when it's picked again.
        val list = session.servers.map { if (isCurrent(it)) it.copy(lastChannelId = session.lastChannelId, recentChannels = session.recentChannels) else it }
        val kept = list.firstOrNull { it.sameAs(s) }
        val entry = if (kept != null) s.copy(lastChannelId = kept.lastChannelId, recentChannels = kept.recentChannels) else s
        val moving = !isCurrent(s)
        session.servers = listOf(entry) + list.filterNot { it.sameAs(s) }
        if (moving) session.restoreChannels(entry)
        session.serverUrl = s.url
        session.userId = s.userId
        session.userName = s.userName
        session.accessToken = s.token
        session.backend = s.backend
        if (s.isNextPvr) {
            api = null
            nextPvr = NextPvrBackend(s.url, s.token!!)
        } else {
            nextPvr = null
            api = jellyfin.createApi(baseUrl = s.url, accessToken = s.token)
        }
        jellyfinBackendMade = null
    }

    /** Signs out of this server: it's forgotten too, so getting back in needs a sign-in. */
    fun signOut() {
        session.servers = session.servers.filterNot { isCurrent(it) }
        session.clear()
        api = null
        nextPvr = null
    }

    /** What this TV can play without transcoding (from Jellyfin Android TV, via Wholphin). */
    val deviceProfile: DeviceProfile by lazy {
        createDeviceProfile(
            mediaTest = MediaCodecCapabilitiesTest(appContext),
            maxBitrate = 120_000_000,
            isAC3Enabled = true,
            downMixAudio = false,
            assDirectPlay = false,
            pgsDirectPlay = true,
            dolbyVisionELDirectPlay = false,
            decodeAv1 = false,
            jellyfinTenEleven = true,
            preferAc3ForSurround = false,
        )
    }
}
