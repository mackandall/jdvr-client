package com.mackandall.jdvr.data

import android.content.Context
import com.mackandall.jdvr.BuildConfig
import com.mackandall.jdvr.profile.MediaCodecCapabilitiesTest
import com.mackandall.jdvr.profile.createDeviceProfile
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.android.androidDevice
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

    private val jellyfinTv by lazy { LiveTvRepository() }
    private var nextPvr: NextPvrBackend? = null

    /** The signed-in server's calls: Jellyfin's, or NextPVR's. */
    val liveTv: TvBackend get() = nextPvr ?: jellyfinTv

    val isNextPvr: Boolean get() = nextPvr != null
    /** NextPVR's own calls (series options), when signed in to one. */
    val nextPvrBackend: NextPvrBackend? get() = nextPvr

    fun init(context: Context) {
        appContext = context.applicationContext
        session = Session(appContext)
        prefs = Prefs(appContext)
        http = OkHttpClient.Builder().addInterceptor(NextPvrBackend.snapshotLength).addInterceptor(NextPvrBackend.LiveHold.interceptor).build()
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
        }
    }

    val signedIn: Boolean get() = nextPvr != null || api != null && userId != null

    fun signIn(
        url: String,
        userId: UUID,
        userName: String?,
        token: String,
    ) {
        session.serverUrl = url
        session.userId = userId.toString()
        session.userName = userName
        session.accessToken = token
        session.backend = "jellyfin"
        nextPvr = null
        api = jellyfin.createApi(baseUrl = url, accessToken = token)
    }

    /** A NextPVR server: kept is the md5 of its PIN (encrypted, like a token), to sign in again. */
    fun signInNextPvr(
        url: String,
        pinMd5: String,
    ) {
        session.serverUrl = url
        session.userId = null
        session.userName = null
        session.accessToken = pinMd5
        session.backend = "nextpvr"
        api = null
        nextPvr = NextPvrBackend(url, pinMd5)
    }

    fun signOut() {
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
