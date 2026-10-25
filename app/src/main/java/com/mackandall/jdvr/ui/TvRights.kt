package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.DeviceRights
import com.mackandall.jdvr.data.attempt

/**
 * What this TV may do (JellyDVR Space's settings page, "JDVR devices"). An answer is reused for
 * 2 minutes, so a change on the server shows up. Without an answer (no JellyDVR Space, or the
 * server unreachable) the app behaves as it did before there were rights: everything but
 * deleting from the archive libraries.
 */
object TvRights {
    var info by mutableStateOf(DeviceRights())
        private set

    // Whether the server answered: only then are the rights someone's choice.
    var managed by mutableStateOf(false)
        private set
    private var forUser: String? = null
    private var at = 0L

    suspend fun load() {
        val user = Jdvr.serverKey
        if (user != forUser) at = 0
        if (System.currentTimeMillis() - at < 2 * 60_000) return
        val got = attempt { Jdvr.backend.deviceRights() }
        info = got ?: DeviceRights()
        managed = got != null
        forUser = user
        at = if (got != null) System.currentTimeMillis() else 0L
    }
}

/** This TV's rights, refreshed when a page that uses them opens. */
@Composable
fun deviceRights(): DeviceRights {
    LaunchedEffect(Jdvr.serverKey) { TvRights.load() }
    return TvRights.info
}
