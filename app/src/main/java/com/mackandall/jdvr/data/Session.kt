package com.mackandall.jdvr.data

import android.content.Context

/** The signed-in server and user, kept across restarts. */
class Session(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("serverUrl", null)
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    var userId: String?
        get() = prefs.getString("userId", null)
        set(v) = prefs.edit().putString("userId", v).apply()

    var userName: String?
        get() = prefs.getString("userName", null)
        set(v) = prefs.edit().putString("userName", v).apply()

    var accessToken: String?
        get() = prefs.getString("accessToken", null)
        set(v) = prefs.edit().putString("accessToken", v).apply()

    /** The last live channel watched (opened again at start, SageTV style). */
    var lastChannelId: String?
        get() = prefs.getString("lastChannelId", null)
        set(v) = prefs.edit().putString("lastChannelId", v).apply()

    fun clear() = prefs.edit().clear().apply()
}
