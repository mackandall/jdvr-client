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
    // Forget needs Episode History; on a server without it the button isn't offered.
    var canForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { canForget = Jdvr.liveTv.hasEpisodeHistory() }
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
    var armedFind by remember(itemId) { mutableStateOf(false) }
    var armedClearStart by remember(itemId) { mutableStateOf(false) }
    var armedAudio by remember(itemId) { mutableStateOf(false) }
    // Comskip's tools change the recording: only for users allowed to edit recordings.
    val edit = editPermission()
    val editTools = tools && edit != null
    LaunchedEffect(itemId, startCheck, editTools) { if (editTools) start = runCatching { Jdvr.liveTv.programStartStatus(itemId) }.getOrNull() }
    LaunchedEffect(armedUndo, armedFind, armedClearStart, armedAudio) {
        if (armedUndo || armedFind || armedClearStart || armedAudio) {
            delay(4000)
            armedUndo = false
            armedFind = false
            armedClearStart = false
            armedAudio = false
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
        CuttingBar(cut, onStop = if (cut.stoppable && edit != null) { { scope.launch { onNote(runCatching { Jdvr.liveTv.stopCut(itemId)?.message }.getOrNull() ?: "Couldn't stop it."); MoveWatcher.kick() } } } else null)
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
    // Comskip's tools, as in the web client's item menu (users allowed to edit recordings).
    // Comskip: scan, or remove what a scan found (in case it shouldn't have run); the button
    // follows the item's status, and while a scan runs it checks back until it's done.
    if (editTools && cut == null) {
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
                            onNote(r?.message?.ifEmpty { null } ?: "Couldn't remove them.")
                            commercialsCheck++
                        }
                    }
                }
            else ->
                Button("Scan for commercials") {
                    scope.launch {
                        onNote(runCatching { Jdvr.liveTv.scanCommercials(itemId)?.message }.getOrNull() ?: "Couldn't start a scan.")
                        commercialsCheck++
                    }
                }
        }
    }
    // Program start: a remembered channel card (set from the player) found in the opening.
    // A found start is marked or trimmed per the plugin's setting: the second press says which.
    if (editTools && cut == null) {
        Button(
            when {
                !armedFind -> "Find program start"
                edit!!.programStartTrims -> "Press again: find it and cut before it. ${edit.undoNote}"
                else -> "Press again: find it and skip to it"
            },
        ) {
            if (!armedFind) {
                armedFind = true
            } else {
                armedFind = false
                onNote("Looking for a remembered card…")
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.findProgramStart(itemId)?.message }.getOrNull() ?: "Couldn't look: something may already be working on it.")
                    MoveWatcher.kick()
                }
            }
        }
    }
    val ps = start
    if (editTools && cut == null && ps?.action == "marker" && (ps.startSeconds ?: 0.0) >= 1) {
        val s = ps.startSeconds!!.toInt()
        Button(if (armedClearStart) "Press again: play from the very start again" else "Remove program start (skips to ${s / 60}:${"%02d".format(s % 60)})") {
            if (!armedClearStart) {
                armedClearStart = true
            } else {
                armedClearStart = false
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.clearProgramStart(itemId)?.message }.getOrNull() ?: "Couldn't remove it.")
                    startCheck++
                }
            }
        }
    }
    if (editTools && cut == null && ps?.canUndo == true) {
        val until = ps.undoUntil?.let { untilDate(it) }?.let { ", until $it" } ?: ""
        Button(if (armedUndo) "Press again: put the original back" else "Undo trim (put the original back$until)") {
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
    if (editTools && cut == null) {
        Button(if (armedAudio) "Press again: cut off audio in the wrong format. ${edit!!.undoNote}" else "Fix audio format") {
            if (!armedAudio) {
                armedAudio = true
            } else {
                armedAudio = false
                scope.launch {
                    onNote(runCatching { Jdvr.liveTv.fixAudioFormat(itemId)?.message }.getOrNull() ?: "Couldn't fix it: something may already be working on it.")
                    MoveWatcher.kick()
                }
            }
        }
    }
    // Episode History's "Forget this episode": delete it and forget it, so it records again.
    if (tools && manage && cut == null && canForget) {
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

// "Oct 14" from the plugin's UTC timestamp (local date).
private fun untilDate(utc: String): String? = runCatching {
    java.time.OffsetDateTime.parse(if (utc.endsWith("Z") || utc.contains('+')) utc else utc + "Z")
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
}.getOrNull()

/**
 * What a whole show offers in its quick actions: move all its recordings to the archive
 * (JellyDVR Space; administrators), and delete all of them (Episode History; those the user may
 * delete, in the recordings folders, so archived copies stay). Deleting asks for a second press
 * and says how many and how much first. [onDeleted] follows a delete or a finished move.
 */
@Composable
fun ShowActions(
    seriesId: UUID,
    onNote: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var delete by remember(seriesId) { mutableStateOf<LiveTvRepository.ShowDelete?>(null) }
    var armedDelete by remember(seriesId) { mutableStateOf(false) }
    var deleting by remember(seriesId) { mutableStateOf(false) }
    var archive by remember(seriesId) { mutableStateOf<LiveTvRepository.ArchiveChoices?>(null) }
    var archiveCheck by remember(seriesId) { mutableIntStateOf(0) }
    var picking by remember(seriesId) { mutableStateOf(false) }
    LaunchedEffect(seriesId) { delete = runCatching { Jdvr.liveTv.deleteShowRecordings(seriesId, dryRun = true) }.getOrNull() }
    LaunchedEffect(seriesId, archiveCheck) {
        archive = runCatching { Jdvr.liveTv.archiveChoices(seriesId) }.getOrNull()
        // Moves under way: check back until they're done.
        while (archive?.inFlight != null) {
            delay(5000)
            archive = runCatching { Jdvr.liveTv.archiveChoices(seriesId) }.getOrNull()
        }
    }
    LaunchedEffect(armedDelete) {
        if (armedDelete) {
            delay(4000)
            armedDelete = false
        }
    }

    val a = archive
    if (a != null && a.isRecording) {
        if (a.inFlight != null) {
            Button("Moving to archive… (${a.inFlight})") {}
        } else {
            Button(if (a.count > 1) "Move all ${a.count} recordings to archive…" else "Move to archive…") {
                scope.launch {
                    // Ask again: free space may have changed since the menu opened.
                    archive = runCatching { Jdvr.liveTv.archiveChoices(seriesId) }.getOrNull() ?: archive
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
                        val r = runCatching { Jdvr.liveTv.archive(seriesId, t.path) }.getOrNull()
                        onNote(r?.message?.ifEmpty { null } ?: "Couldn't move them (administrators only, needs JellyDVR Space).")
                        MoveWatcher.kick()
                        archiveCheck++
                    }
                },
                onDismiss = { picking = false },
            )
        }
    }
    val d = delete
    if (d != null && d.count > 0) {
        val what = "${d.count} recording${if (d.count == 1) "" else "s"}"
        val size = "%.1f GB".format(d.bytes / (1024.0 * 1024 * 1024))
        Button(
            when {
                deleting -> "Deleting…"
                armedDelete -> "Press again: delete $what ($size) for good"
                else -> "Delete all $what"
            },
            leading = { Dot(Jc.Recording) },
        ) {
            if (deleting) return@Button
            if (!armedDelete) {
                armedDelete = true
            } else {
                armedDelete = false
                deleting = true
                scope.launch {
                    val r = runCatching { Jdvr.liveTv.deleteShowRecordings(seriesId, dryRun = false) }.getOrNull()
                    deleting = false
                    if (r == null) {
                        onNote("Couldn't delete them.")
                    } else {
                        onNote("Deleted ${r.deleted} recording${if (r.deleted == 1) "" else "s"}" + if (r.skipped > 0) " (${r.skipped} left: recording now, or not yours to delete)" else "")
                        onDeleted()
                    }
                }
            }
        }
    }
}
