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

    /** Live channels watched, most recent first (the mini guide's Recent strip). */
    val recentChannels: List<String>
        get() = prefs.getString("recentChannels", "")!!.split(',').filter { it.isNotBlank() }

    fun pushRecentChannel(id: String) {
        val list = (listOf(id) + recentChannels.filter { it != id }).take(8)
        prefs.edit().putString("recentChannels", list.joinToString(",")).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
