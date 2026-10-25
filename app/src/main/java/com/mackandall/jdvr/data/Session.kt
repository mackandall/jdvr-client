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

    /** "nextpvr" for a NextPVR server; Jellyfin otherwise. */
    var backend: String?
        get() = prefs.getString("backend", null)
        set(v) = prefs.edit().putString("backend", v).apply()

    var userId: String?
        get() = prefs.getString("userId", null)
        set(v) = prefs.edit().putString("userId", v).apply()

    var userName: String?
        get() = prefs.getString("userName", null)
        set(v) = prefs.edit().putString("userName", v).apply()

    /**
     * The server's access token, encrypted with a key kept in the device's Android keystore (it
     * can't be read out of the app's files without it). A token saved by an older version in
     * plain text is encrypted on first read. If the keystore fails, it's kept as before.
     */
    var accessToken: String?
        get() {
            prefs.getString("accessTokenEnc", null)?.let { enc -> return TokenCipher.decrypt(enc) }
            val plain = prefs.getString("accessToken", null) ?: return null
            TokenCipher.encrypt(plain)?.let {
                prefs
                    .edit()
                    .putString("accessTokenEnc", it)
                    .remove("accessToken")
                    .apply()
            }
            return plain
        }
        set(v) {
            val enc = v?.let { TokenCipher.encrypt(it) }
            prefs
                .edit()
                .apply {
                    if (v == null) {
                        remove("accessTokenEnc")
                        remove("accessToken")
                    } else if (enc != null) {
                        putString("accessTokenEnc", enc)
                        remove("accessToken")
                    } else {
                        putString("accessToken", v)
                        remove("accessTokenEnc")
                    }
                }.apply()
        }

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

    /** Forgets the signed-in server; the remembered ones stay. */
    fun clear() =
        prefs
            .edit()
            .apply {
                prefs.all.keys
                    .filter { it != "servers" }
                    .forEach { remove(it) }
            }.apply()

    /**
     * Servers signed in to before, most recent first, each with its token (encrypted like the
     * current one) and its last and recent channels, so switching back needs no sign-in.
     */
    var servers: List<SavedServer>
        get() =
            runCatching {
                val arr = org.json.JSONArray(prefs.getString("servers", "[]"))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SavedServer(
                        backend = o.optString("backend", "jellyfin"),
                        url = o.getString("url"),
                        name = o.optString("name").ifEmpty { o.getString("url") },
                        userId = o.optString("userId").ifEmpty { null },
                        userName = o.optString("userName").ifEmpty { null },
                        token = o.optString("token").ifEmpty { null }?.let { TokenCipher.decrypt(it) },
                        lastChannelId = o.optString("lastChannelId").ifEmpty { null },
                        recentChannels = o.optString("recentChannels").split(',').filter { it.isNotBlank() },
                    )
                }
            }.getOrDefault(emptyList())
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { s ->
                // A token that can't be encrypted isn't kept: that server asks to sign in again.
                arr.put(
                    org.json
                        .JSONObject()
                        .put("backend", s.backend)
                        .put("url", s.url)
                        .put("name", s.name)
                        .put("userId", s.userId ?: "")
                        .put("userName", s.userName ?: "")
                        .put("token", s.token?.let { TokenCipher.encrypt(it) } ?: "")
                        .put("lastChannelId", s.lastChannelId ?: "")
                        .put("recentChannels", s.recentChannels.joinToString(",")),
                )
            }
            prefs.edit().putString("servers", arr.toString()).apply()
        }

    fun restoreChannels(s: SavedServer) {
        prefs
            .edit()
            .putString("lastChannelId", s.lastChannelId)
            .putString("recentChannels", s.recentChannels.joinToString(","))
            .apply()
    }
}

/** A server signed in to before (see [Session.servers]); token null: it asks to sign in again. */
data class SavedServer(
    val backend: String,
    val url: String,
    val name: String,
    val userId: String?,
    val userName: String?,
    val token: String?,
    val lastChannelId: String? = null,
    val recentChannels: List<String> = emptyList(),
) {
    val isNextPvr: Boolean get() = backend == "nextpvr"

    // The same server and user (signing in again replaces it).
    fun sameAs(o: SavedServer) = backend == o.backend && url == o.url && userId == o.userId
}

/** AES-GCM with a key that never leaves the Android keystore. */
private object TokenCipher {
    private const val ALIAS = "jdvr-session"

    private fun key(): javax.crypto.SecretKey {
        val ks =
            java.security.KeyStore
                .getInstance("AndroidKeyStore")
                .apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec
                .Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? =
        runCatching {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        }.getOrNull()

    /** Null when it can't be decrypted (e.g. the key was lost): the app then asks to sign in again. */
    fun decrypt(enc: String): String? =
        runCatching {
            val all = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        }.getOrNull()
}
