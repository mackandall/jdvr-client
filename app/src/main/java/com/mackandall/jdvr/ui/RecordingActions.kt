package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What a recording offers besides playing it: add its show to Recording Favourites (or, if it's
 * one already, take it off), and delete it. Taking a favourite off and deleting both ask for a
 * second press. [onNote] reports what happened; [onDeleted] follows a delete.
 */
@Composable
fun RecordingActions(
    itemId: UUID,
    onNote: (String) -> Unit,
    // Comskip's tools (scan, audio fix): on the details page, not in the show page's short row.
    tools: Boolean = true,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fav by remember(itemId) { mutableStateOf<LiveTvRepository.FavouriteFor?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var armedDelete by remember(itemId) { mutableStateOf(false) }
    var armedUnfav by remember(itemId) { mutableStateOf(false) }
    var armedForget by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId, reload) { fav = runCatching { Jdvr.liveTv.favouriteForItem(itemId) }.getOrNull() }
    LaunchedEffect(armedDelete, armedUnfav, armedForget) {
        if (armedDelete || armedUnfav || armedForget) {
            delay(4000)
            armedDelete = false
            armedUnfav = false
            armedForget = false
        }
    }

    val f = fav
    if (f != null && !f.isMovie) {
        val rule = f.favourite
        if (rule == null) {
            Button("★ Add to Recording Favourites") {
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.addFavourite(itemId)?.message ?: "Added ${f.title} to Recording Favourites" }.getOrElse { "Couldn't add it: ${it.message}" })
                    reload++
                }
            }
        } else {
            Button(if (armedUnfav) "Press again: remove from favourites" else "★ In Recording Favourites") {
                if (!armedUnfav) {
                    armedUnfav = true
                } else {
                    armedUnfav = false
                    scope.launch {
                        onNote(runCatching { Jdvr.liveTv.removeFavourite(rule.id)?.message ?: "Removed ${rule.title} from Recording Favourites" }.getOrElse { "Couldn't remove it: ${it.message}" })
                        reload++
                    }
                }
            }
        }
    }
    // Comskip's tools, as in the web client's item menu (administrators).
    if (tools) Button("Scan for commercials") {
        scope.launch { onNote(runCatching { Jdvr.liveTv.scanCommercials(itemId)?.message }.getOrNull() ?: "Couldn't start a scan (administrators only).") }
    }
    if (tools) Button("Fix audio format") {
        onNote("Fixing the audio format: this can take a minute…")
        scope.launch { onNote(runCatching { Jdvr.liveTv.fixAudioFormat(itemId)?.message }.getOrNull() ?: "Couldn't fix it (administrators only).") }
    }
    // Episode History's "Forget this episode": delete it and forget it, so it records again.
    if (tools) {
        Button(if (armedForget) "Press again: delete and forget" else "Forget this episode") {
            if (!armedForget) {
                armedForget = true
            } else {
                armedForget = false
                scope.launch {
                    val r = runCatching { Jdvr.liveTv.forgetRecording(itemId) }.getOrNull()
                    if (r == null) {
                        onNote("Couldn't forget it (administrators only, needs Episode History).")
                    } else {
                        onNote("Deleted and forgotten: it will be recorded again" + if (r.keptInLibrary > 0) " (${r.keptInLibrary} other copy still in the library counts as recorded)" else "")
                        onDeleted()
                    }
                }
            }
        }
    }
    Button(if (armedDelete) "Press again to delete" else "Delete recording", leading = { Dot(Jc.Recording) }) {
        if (!armedDelete) {
            armedDelete = true
        } else {
            armedDelete = false
            scope.launch {
                runCatching { Jdvr.liveTv.deleteRecording(itemId) }
                    .onSuccess {
                        onNote(if (it.deleted > 1) "Partial recording deleted, with its ${it.deleted - 1} other partial piece${if (it.deleted > 2) "s" else ""}" else "Recording deleted")
                        onDeleted()
                    }.onFailure { onNote("Couldn't delete it: ${it.message}") }
            }
        }
    }
}
