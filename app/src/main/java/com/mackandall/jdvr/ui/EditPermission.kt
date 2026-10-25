package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Editing
import com.mackandall.jdvr.data.attempt

/**
 * Whether the signed-in user may edit recordings (Comskip plugin: administrators, and the users
 * its config page allows), and what a trim keeps. An answer is reused for 5 minutes, so a change on
 * the server shows up. No answer (Comskip removed, or the server unreachable) means no edit
 * buttons, and it's asked again on the next page rather than after 5 minutes, so the buttons come
 * back as soon as the plugin does.
 */
object EditPermission {
    var info by mutableStateOf<Editing?>(null)
        private set
    private var forUser: String? = null
    private var at = 0L

    suspend fun load() {
        val user = Jdvr.serverKey
        if (user != forUser) {
            info = null
            at = 0
        }
        if (System.currentTimeMillis() - at < 5 * 60_000) return
        val got = attempt { Jdvr.backend.editing() }
        info = got
        forUser = user
        at = if (got != null) System.currentTimeMillis() else 0L
    }
}

/** The signed-in user's edit permission; null (no edit buttons) until known. */
@Composable
fun editPermission(): Editing? {
    LaunchedEffect(Jdvr.serverKey) { EditPermission.load() }
    return EditPermission.info?.takeIf { it.canEdit }
}
