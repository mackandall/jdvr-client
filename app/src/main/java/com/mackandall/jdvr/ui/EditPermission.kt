package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository

/**
 * Whether the signed-in user may edit recordings (Comskip plugin: administrators, and the users
 * its config page allows), and what a trim keeps. Asked once per user, again after 5 minutes so a
 * change on the server shows up. Until the answer arrives nobody gets edit buttons.
 */
object EditPermission {
    var info by mutableStateOf<LiveTvRepository.Editing?>(null)
        private set
    private var forUser: String? = null
    private var at = 0L

    suspend fun load() {
        val user = Jdvr.userId?.toString()
        if (user != forUser) {
            info = null
            at = 0
        }
        if (System.currentTimeMillis() - at < 5 * 60_000) return
        val got = runCatching { Jdvr.liveTv.editing() }.getOrNull()
        // A failed answer keeps the last one (or none: no buttons).
        if (got != null || user != forUser) info = got
        forUser = user
        at = System.currentTimeMillis()
    }
}

/** The signed-in user's edit permission; null (no edit buttons) until known. */
@Composable
fun editPermission(): LiveTvRepository.Editing? {
    LaunchedEffect(Jdvr.userId) { EditPermission.load() }
    return EditPermission.info?.takeIf { it.canEdit }
}
