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
    // Favourites, Forget and Delete: for recordings in the live folder. Archive copies (on the
    // NAS) get only the tools, so nothing deletes them from the TV.
    manage: Boolean = true,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fav by remember(itemId) { mutableStateOf<LiveTvRepository.FavouriteFor?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var armedDelete by remember(itemId) { mutableStateOf(false) }
    var armedUnfav by remember(itemId) { mutableStateOf(false) }
    var armedForget by remember(itemId) { mutableStateOf(false) }
    var armedRemove by remember(itemId) { mutableStateOf(false) }
    var commercials by remember(itemId) { mutableStateOf<LiveTvRepository.CommercialStatus?>(null) }
    var commercialsCheck by remember(itemId) { mutableIntStateOf(0) }
    LaunchedEffect(itemId, commercialsCheck) {
        commercials = runCatching { Jdvr.liveTv.commercialStatus(itemId) }.getOrNull()
        // A scan in progress: check again every few seconds until it's done.
        while (commercials?.inFlight == true) {
            delay(4000)
            commercials = runCatching { Jdvr.liveTv.commercialStatus(itemId) }.getOrNull()
        }
    }
    LaunchedEffect(itemId, reload) { fav = runCatching { Jdvr.liveTv.favouriteForItem(itemId) }.getOrNull() }
    // JellyDVR Space: "Move to archive…" for recordings in the live folder. While a move runs it
    // checks back; once the recording has moved, this item is gone (the archive copy is a new
    // item), so the page closes.
    var archive by remember(itemId) { mutableStateOf<LiveTvRepository.ArchiveChoices?>(null) }
    var archiveCheck by remember(itemId) { mutableIntStateOf(0) }
    var picking by remember(itemId) { mutableStateOf(false) }
    // Program start: a skip marker to remove, or a trim whose original was kept to undo.
    var start by remember(itemId) { mutableStateOf<LiveTvRepository.ProgramStartStatus?>(null) }
    var startCheck by remember(itemId) { mutableIntStateOf(0) }
    var armedUndo by remember(itemId) { mutableStateOf(false) }
    LaunchedEffect(itemId, startCheck) { if (tools) start = runCatching { Jdvr.liveTv.programStartStatus(itemId) }.getOrNull() }
    LaunchedEffect(armedUndo) {
        if (armedUndo) {
            delay(4000)
            armedUndo = false
        }
    }
    LaunchedEffect(itemId, archiveCheck) {
        if (!manage) return@LaunchedEffect
        archive = runCatching { Jdvr.liveTv.archiveChoices(itemId) }.getOrNull()
        var wasMoving = false
        while (archive?.inFlight != null) {
            wasMoving = true
            delay(5000)
            archive = runCatching { Jdvr.liveTv.archiveChoices(itemId) }.getOrNull()
        }
        if (wasMoving && archive == null) {
            onNote("Moved to the archive")
            onDeleted()
        }
    }
    LaunchedEffect(armedDelete, armedUnfav, armedForget, armedRemove) {
        if (armedDelete || armedUnfav || armedForget || armedRemove) {
            delay(4000)
            armedDelete = false
            armedUnfav = false
            armedForget = false
            armedRemove = false
        }
    }

    // Being cut (program start, audio format): its progress and Stop, and nothing else that
    // touches the file until it's done (a second cut, a scan, a move or a delete meanwhile
    // would work on a file about to be replaced).
    val cut = cuttingFor(itemId.toString())
    if (cut != null) {
        CuttingBar(cut, onStop = if (cut.stoppable) { { scope.launch { onNote(runCatching { Jdvr.liveTv.stopCut(itemId)?.message }.getOrNull() ?: "Couldn't stop it."); MoveWatcher.kick() } } } else null)
    }
    val f = fav
    if (manage && f != null && !f.isMovie) {
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
    // Comskip: scan, or remove what a scan found (in case it shouldn't have run); the button
    // follows the item's status, and while a scan runs it checks back until it's done.
    if (tools && cut == null) {
        val c = commercials
        when {
            c?.inFlight == true -> Button("Scanning for commercials…") {}
            c?.found == true ->
                Button(if (armedRemove) "Press again: remove commercials" else "Remove commercials") {
                    if (!armedRemove) {
                        armedRemove = true
                    } else {
                        armedRemove = false
                        scope.launch {
                            val r = runCatching { Jdvr.liveTv.removeCommercials(itemId) }.getOrNull()
                            onNote(r?.message?.ifEmpty { null } ?: "Couldn't remove them (administrators only).")
                            commercialsCheck++
                        }
                    }
                }
            else ->
                Button("Scan for commercials") {
                    scope.launch {
                        onNote(runCatching { Jdvr.liveTv.scanCommercials(itemId)?.message }.getOrNull() ?: "Couldn't start a scan (administrators only).")
                        commercialsCheck++
                    }
                }
        }
    }
    // Program start: a remembered channel card (set from the player) found in the opening.
    if (tools && cut == null) Button("Find program start") {
        onNote("Looking for a remembered card…")
        scope.launch {
            onNote(runCatching { Jdvr.liveTv.findProgramStart(itemId)?.message }.getOrNull() ?: "Couldn't look: something may already be working on it (or you're not an administrator).")
            MoveWatcher.kick()
        }
    }
    val ps = start
    if (tools && cut == null && ps?.action == "marker" && (ps.startSeconds ?: 0.0) >= 1) {
        val s = ps.startSeconds!!.toInt()
        Button("Remove program start (skips to ${s / 60}:${"%02d".format(s % 60)})") {
            scope.launch {
                onNote(runCatching { Jdvr.liveTv.clearProgramStart(itemId)?.message }.getOrNull() ?: "Couldn't remove it.")
                startCheck++
            }
        }
    }
    if (tools && cut == null && ps?.canUndo == true) {
        Button(if (armedUndo) "Press again: put the original back" else "Undo trim (put the original back)") {
            if (!armedUndo) {
                armedUndo = true
            } else {
                armedUndo = false
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.undoTrim(itemId)?.message }.getOrNull() ?: "Couldn't undo it.")
                    startCheck++
                }
            }
        }
    }
    if (tools && cut == null) Button("Fix audio format") {
        scope.launch {
            onNote(runCatching { Jdvr.liveTv.fixAudioFormat(itemId)?.message }.getOrNull() ?: "Couldn't fix it: something may already be working on it (or you're not an administrator).")
            MoveWatcher.kick()
        }
    }
    // Episode History's "Forget this episode": delete it and forget it, so it records again.
    if (tools && manage && cut == null) {
        Button(if (armedForget) "Press again: delete and forget" else if (fav?.isMovie == true) "Forget this movie" else "Forget this episode") {
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
    val a = archive
    if (manage && a != null && a.isRecording && cut == null) {
        if (a.inFlight != null) {
            MovingBar(movingFor(itemId.toString()) ?: a.progress)
        } else {
            Button("Move to archive…") {
                scope.launch {
                    // Ask again: free space may have changed since the page opened.
                    archive = runCatching { Jdvr.liveTv.archiveChoices(itemId) }.getOrNull() ?: archive
                    picking = true
                }
            }
        }
    }
    if (picking) {
        archive?.let { choices ->
            ArchivePicker(
                choices,
                onPick = { t ->
                    picking = false
                    scope.launch {
                        val r = runCatching { Jdvr.liveTv.archive(itemId, t.path) }.getOrNull()
                        onNote(r?.message?.ifEmpty { null } ?: "Couldn't move it (administrators only, needs JellyDVR Space).")
                        MoveWatcher.kick()
                        archiveCheck++
                    }
                },
                onDismiss = { picking = false },
            )
        }
    }
    if (manage && cut == null) Button(if (armedDelete) "Press again to delete" else "Delete recording", leading = { Dot(Jc.Recording) }) {
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
