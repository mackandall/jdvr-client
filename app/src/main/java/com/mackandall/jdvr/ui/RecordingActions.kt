package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.ActiveRecordings
import com.mackandall.jdvr.data.ArchiveChoices
import com.mackandall.jdvr.data.CommercialStatus
import com.mackandall.jdvr.data.FavouriteFor
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.ProgramStartStatus
import com.mackandall.jdvr.data.ShowDelete
import com.mackandall.jdvr.data.ShowForget
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.attemptResult
import com.mackandall.jdvr.data.toUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * "Watch live" for each recording in progress behind an item: tunes the channel it is recorded
 * from (Jellyfin shares the recording's stream, so no extra tuner). Shown to anyone; needs Episode
 * History's channel in its active recordings, and a place that can play a channel.
 */
@Composable
fun WatchLiveButtons(itemId: UUID) {
    val watch = LocalWatchLive.current ?: return
    var active by remember(itemId) { mutableStateOf<ActiveRecordings?>(null) }
    LaunchedEffect(itemId) { active = attempt { Jdvr.backend.activeRecordings(itemId) } }
    val list = active?.recordings.orEmpty().filter { it.channelId?.toUuid() != null }
    list.forEach { r ->
        // ▶ as in the guide: the red dot is for what changes the recording (Stop), not for watching it.
        Button(if (list.size > 1) "Watch live: ${r.name}" else "Watch live", leading = { Text("▶", color = Jc.Text) }) {
            r.channelId?.toUuid()?.let(watch)
        }
    }
}

/**
 * "Stop recording" for an item being recorded right now: a recording, or a show with an episode
 * being recorded (one button per recording, named when there are several). Only on a TV allowed
 * to record, for a user who may manage Live TV. Asks for a second press; what was recorded so
 * far is kept.
 */
@Composable
fun StopRecordingButtons(
    itemId: UUID,
    onNote: (String) -> Unit,
    onStopped: () -> Unit = {},
) {
    val rights = deviceRights()
    val scope = rememberCoroutineScope()
    var active by remember(itemId) { mutableStateOf<ActiveRecordings?>(null) }
    var check by remember(itemId) { mutableIntStateOf(0) }
    var armed by remember(itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(itemId, check) { active = attempt { Jdvr.backend.activeRecordings(itemId) } }
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(4000)
            armed = null
        }
    }
    val a = active
    if (!rights.record || a == null || !a.canStop) return
    a.recordings.forEach { r ->
        val what = if (a.recordings.size > 1) ": ${r.name}" else ""
        Button(if (armed == r.id) "Press again to stop recording$what" else "Stop recording$what", leading = { Dot(Jc.Recording) }) {
            if (armed != r.id) {
                armed = r.id
            } else {
                armed = null
                scope.launch {
                    val stopped = attempt { Jdvr.backend.stopRecording(r.id) } ?: false
                    onNote(if (stopped) "Recording stopped: what was recorded so far is kept" else "Couldn't stop it (it may have just ended).")
                    check++
                    if (stopped) onStopped()
                }
            }
        }
    }
}

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
    // NAS) get the tools, and Delete only on a TV allowed to delete from the archive.
    manage: Boolean = true,
    // Whether Jellyfin lets the user delete it, and the share it's on (for an archive copy).
    canDelete: Boolean = true,
    place: String? = null,
    onDeleted: () -> Unit,
) {
    val rights = deviceRights()
    val scope = rememberCoroutineScope()
    var fav by remember(itemId) { mutableStateOf<FavouriteFor?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var armedDelete by remember(itemId) { mutableStateOf(false) }
    // Forget needs Episode History; on a server without it the button isn't offered.
    var canForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { canForget = Jdvr.backend.hasEpisodeHistory() }
    var armedUnfav by remember(itemId) { mutableStateOf(false) }
    var armedForget by remember(itemId) { mutableStateOf(false) }
    var armedRemove by remember(itemId) { mutableStateOf(false) }
    var commercials by remember(itemId) { mutableStateOf<CommercialStatus?>(null) }
    var commercialsCheck by remember(itemId) { mutableIntStateOf(0) }
    LaunchedEffect(itemId, commercialsCheck) {
        commercials = attempt { Jdvr.backend.commercialStatus(itemId) }
        // A scan in progress: check again every few seconds until it's done.
        val scanned = commercials?.inFlight == true
        while (commercials?.inFlight == true) {
            delay(4000)
            commercials = attempt { Jdvr.backend.commercialStatus(itemId) }
        }
        if (scanned) CommercialMarks.changed++
    }
    LaunchedEffect(itemId, reload) { fav = attempt { Jdvr.backend.favouriteForItem(itemId) } }
    // JellyDVR Space: "Move to archive…" for recordings in the live folder. While a move runs it
    // checks back; once the recording has moved, this item is gone (the archive copy is a new
    // item), so the page closes.
    var archive by remember(itemId) { mutableStateOf<ArchiveChoices?>(null) }
    var archiveCheck by remember(itemId) { mutableIntStateOf(0) }
    var picking by remember(itemId) { mutableStateOf(false) }
    // Program start: a skip marker to remove, or a trim whose original was kept to undo.
    var start by remember(itemId) { mutableStateOf<ProgramStartStatus?>(null) }
    var startCheck by remember(itemId) { mutableIntStateOf(0) }
    var armedUndo by remember(itemId) { mutableStateOf(false) }
    var armedFind by remember(itemId) { mutableStateOf(false) }
    var armedClearStart by remember(itemId) { mutableStateOf(false) }
    var armedAudio by remember(itemId) { mutableStateOf(false) }
    // Comskip's tools change the recording: only for users allowed to edit recordings.
    val edit = editPermission()
    val editTools = tools && edit != null && rights.edit
    LaunchedEffect(itemId, startCheck, editTools) { if (editTools) start = attempt { Jdvr.backend.programStartStatus(itemId) } }
    LaunchedEffect(armedUndo, armedFind, armedClearStart, armedAudio) {
        if (armedUndo || armedFind || armedClearStart || armedAudio) {
            delay(4000)
            armedUndo = false
            armedFind = false
            armedClearStart = false
            armedAudio = false
        }
    }
    LaunchedEffect(itemId, archiveCheck, rights.archive) {
        if (!manage || !rights.archive) return@LaunchedEffect
        archive = attempt { Jdvr.backend.archiveChoices(itemId) }
        var wasMoving = false
        while (archive?.inFlight != null) {
            wasMoving = true
            delay(5000)
            archive = attempt { Jdvr.backend.archiveChoices(itemId) }
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
    // Jellyfin won't delete a file it is recording, so [canDelete] was false while it recorded.
    // After a stop it is asked again (Jellyfin lets go of the file a moment later), so Delete
    // appears here without leaving the page.
    var stops by remember(itemId) { mutableIntStateOf(0) }
    var canDeleteNow by remember(itemId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(itemId, stops) {
        if (stops > 0) {
            repeat(8) {
                val fresh = attempt { Jdvr.backend.details(itemId).canDelete }
                if (fresh == true) {
                    canDeleteNow = true
                    return@LaunchedEffect
                }
                delay(1000)
            }
        }
    }
    @Suppress("NAME_SHADOWING")
    val canDelete = canDeleteNow ?: canDelete
    WatchLiveButtons(itemId)
    StopRecordingButtons(itemId, onNote, onStopped = { stops++ })
    RerecordButtons(itemId, onNote)
    val cut = cuttingFor(itemId.toString())
    if (cut != null) {
        CuttingBar(
            cut,
            onStop =
                if (cut.stoppable && edit != null) {
                    {
                        scope.launch {
                            onNote(attempt { Jdvr.backend.stopCut(itemId)?.message } ?: "Couldn't stop it.")
                            MoveWatcher.kick()
                        }
                    }
                } else {
                    null
                },
        )
    }
    val f = fav
    if (manage && rights.record && f != null && !f.isMovie) {
        val rule = f.favourite
        if (rule == null) {
            Button("★ Add to Recording Favourites") {
                scope.launch {
                    onNote(attemptResult { Jdvr.backend.addFavourite(itemId)?.message ?: "Added ${f.title} to Recording Favourites" }.getOrElse { "Couldn't add it: ${it.message}" })
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
                        onNote(attemptResult { Jdvr.backend.removeFavourite(rule.id)?.message ?: "Removed ${rule.title} from Recording Favourites" }.getOrElse { "Couldn't remove it: ${it.message}" })
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
        // The button changes (scan → scanning → remove): focus stays on it rather than jumping away.
        val comFocus =
            remember(itemId) {
                androidx.compose.ui.focus
                    .FocusRequester()
            }
        // (The old button reports losing focus as it goes, so "just had it" counts too.)
        var comHadFocus by remember(itemId) { mutableStateOf(false) }
        var comLostAt by remember(itemId) { androidx.compose.runtime.mutableLongStateOf(0L) }
        val comModifier =
            androidx.compose.ui.Modifier.focusRequester(comFocus).onFocusChanged {
                if (it.isFocused) {
                    comHadFocus = true
                } else if (comHadFocus) {
                    comHadFocus = false
                    comLostAt = android.os.SystemClock.uptimeMillis()
                }
            }
        LaunchedEffect(c?.inFlight, c?.found) {
            if (comHadFocus || android.os.SystemClock.uptimeMillis() - comLostAt < 1000) {
                // The new button needs a frame to be there.
                comFocus.focusSoon()
            }
        }
        when {
            c?.inFlight == true -> {
                Button("Scanning for commercials…", modifier = comModifier) {}
            }

            c?.found == true -> {
                Button(if (armedRemove) "Press again: remove commercials" else "Remove commercials", modifier = comModifier) {
                    if (!armedRemove) {
                        armedRemove = true
                    } else {
                        armedRemove = false
                        scope.launch {
                            val r = attempt { Jdvr.backend.removeCommercials(itemId) }
                            onNote(r?.message?.ifEmpty { null } ?: "Couldn't remove them.")
                            CommercialMarks.changed++
                            commercialsCheck++
                        }
                    }
                }
            }

            else -> {
                Button("Scan for commercials", modifier = comModifier) {
                    scope.launch {
                        onNote(attempt { Jdvr.backend.scanCommercials(itemId)?.message } ?: "Couldn't start a scan.")
                        commercialsCheck++
                    }
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
                    onNote(attempt { Jdvr.backend.findProgramStart(itemId)?.message } ?: "Couldn't look: something may already be working on it.")
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
                    onNote(attempt { Jdvr.backend.clearProgramStart(itemId)?.message } ?: "Couldn't remove it.")
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
                    onNote(attempt { Jdvr.backend.undoTrim(itemId)?.message } ?: "Couldn't undo it.")
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
                    onNote(attempt { Jdvr.backend.fixAudioFormat(itemId)?.message } ?: "Couldn't fix it: something may already be working on it.")
                    MoveWatcher.kick()
                }
            }
        }
    }
    // Episode History's "Forget this episode": delete it and forget it, so it records again.
    if (tools && manage && rights.delete && canDelete && cut == null && canForget) {
        Button(
            if (armedForget) {
                "Press again: delete and forget"
            } else if (fav?.isMovie == true) {
                "Forget this movie"
            } else {
                "Forget this episode"
            },
        ) {
            if (!armedForget) {
                armedForget = true
            } else {
                armedForget = false
                scope.launch {
                    val r = attempt { Jdvr.backend.forgetRecording(itemId) }
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
    if (manage && rights.archive && a != null && a.isRecording && cut == null) {
        if (a.inFlight != null) {
            MovingBar(movingFor(itemId.toString()) ?: a.progress)
        } else {
            Button("Move to archive…") {
                scope.launch {
                    // Ask again: free space may have changed since the page opened.
                    archive = attempt { Jdvr.backend.archiveChoices(itemId) } ?: archive
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
                        val r = attempt { Jdvr.backend.archive(itemId, t.path) }
                        onNote(r?.message?.ifEmpty { null } ?: "Couldn't move it (administrators only, needs JellyDVR Space).")
                        MoveWatcher.kick()
                        archiveCheck++
                    }
                },
                onDismiss = { picking = false },
            )
        }
    }
    // JellyDVR Space: where this recording's show is kept (episodes only; the server says no for movies).
    if (manage && cut == null) StoreShowButton(itemId, onNote)
    // An archive copy: only where this TV may delete from the archive, and it says where from.
    val from = place?.let { " from $it" } ?: " from the archive"
    val trashDays = rememberTrashDays()
    val deleteLabel =
        when {
            manage -> if (armedDelete) (if (trashDays != null) "Press again: move to the trash" else "Press again to delete") else "Delete recording"
            armedDelete -> if (trashDays != null) "Press again: move to the trash" else "Press again: delete$from for good"
            else -> "Delete$from"
        }
    if (canDelete && (if (manage) rights.delete else rights.deleteArchive) && cut == null) {
        Button(deleteLabel, leading = { TrashMark() }) {
            if (!armedDelete) {
                armedDelete = true
            } else {
                armedDelete = false
                scope.launch {
                    attemptResult { Jdvr.backend.deleteRecording(itemId) }
                        .onSuccess {
                            onNote(
                                (
                                    if (it.deleted > 1) {
                                        "Partial recording deleted, with its ${it.deleted - 1} other partial piece${if (it.deleted > 2) "s" else ""}"
                                    } else if (manage) {
                                        "Recording deleted"
                                    } else {
                                        "Deleted$from"
                                    }
                                ) +
                                    ((if (it.trashed) it.trashDays else null)?.let { d -> ": in the trash for ${daysText(d)}" } ?: ""),
                            )
                            onDeleted()
                        }.onFailure { onNote("Couldn't delete it: ${it.message}") }
                }
            }
        }
    }
}

// "Oct 14" from the plugin's UTC timestamp (local date).
private fun untilDate(utc: String): String? =
    runCatching {
        java.time.OffsetDateTime
            .parse(if (utc.endsWith("Z") || utc.contains('+')) utc else utc + "Z")
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter
                    .ofPattern("MMM d"),
            )
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
    var delete by remember(seriesId) { mutableStateOf<ShowDelete?>(null) }
    var armedDelete by remember(seriesId) { mutableStateOf(false) }
    var deleting by remember(seriesId) { mutableStateOf(false) }
    var archive by remember(seriesId) { mutableStateOf<ArchiveChoices?>(null) }
    var archiveCheck by remember(seriesId) { mutableIntStateOf(0) }
    var picking by remember(seriesId) { mutableStateOf(false) }
    var forget by remember(seriesId) { mutableStateOf<ShowForget?>(null) }
    var armedForget by remember(seriesId) { mutableStateOf(false) }
    // The show's archived copies: only on a TV allowed to delete from the archive.
    var archived by remember(seriesId) { mutableStateOf<ShowDelete?>(null) }
    var armedArchived by remember(seriesId) { mutableStateOf(false) }
    val rights = deviceRights()
    LaunchedEffect(seriesId, rights) {
        delete = if (rights.delete) attempt { Jdvr.backend.deleteShowRecordings(seriesId, dryRun = true) } else null
        archived = if (rights.deleteArchive) attempt { Jdvr.backend.deleteShowRecordings(seriesId, dryRun = true, archive = true) } else null
    }
    LaunchedEffect(seriesId) {
        forget = attempt { Jdvr.backend.forgetShow(seriesId, dryRun = true) }
    }
    WatchLiveButtons(seriesId)
    StopRecordingButtons(seriesId, onNote)
    LaunchedEffect(armedArchived) {
        if (armedArchived) {
            delay(4000)
            armedArchived = false
        }
    }
    LaunchedEffect(armedForget) {
        if (armedForget) {
            delay(4000)
            armedForget = false
        }
    }
    LaunchedEffect(seriesId, archiveCheck, rights.archive) {
        if (!rights.archive) {
            archive = null
            return@LaunchedEffect
        }
        archive = attempt { Jdvr.backend.archiveChoices(seriesId) }
        // Moves under way: check back until they're done.
        while (archive?.inFlight != null) {
            delay(5000)
            archive = attempt { Jdvr.backend.archiveChoices(seriesId) }
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
                    archive = attempt { Jdvr.backend.archiveChoices(seriesId) } ?: archive
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
                        val r = attempt { Jdvr.backend.archive(seriesId, t.path) }
                        onNote(r?.message?.ifEmpty { null } ?: "Couldn't move them (administrators only, needs JellyDVR Space).")
                        MoveWatcher.kick()
                        archiveCheck++
                    }
                },
                onDismiss = { picking = false },
            )
        }
    }
    StoreShowButton(seriesId, onNote)
    // Episode History's "Forget this show": its episodes record again (watched ones too).
    val f = forget
    if (f != null && f.episodes > 0) {
        Button(if (armedForget) "Press again: forget all of ${f.show ?: "this show"}" else "Forget this show (${f.episodes} episode${if (f.episodes == 1) "" else "s"})") {
            if (!armedForget) {
                armedForget = true
            } else {
                armedForget = false
                scope.launch {
                    val r = attempt { Jdvr.backend.forgetShow(seriesId, dryRun = false) }
                    onNote(if (r == null) "Couldn't forget it." else "Forgotten: its episodes can be recorded again")
                    forget = attempt { Jdvr.backend.forgetShow(seriesId, dryRun = true) }
                }
            }
        }
    }
    val trashDays = rememberTrashDays()
    val forGood = if (trashDays != null) "to the trash" else "for good"
    val d = delete
    if (d != null && d.count > 0) {
        val what = "${d.count} recording${if (d.count == 1) "" else "s"}"
        val size = "%.1f GB".format(d.bytes / (1024.0 * 1024 * 1024))
        Button(
            when {
                deleting -> "Deleting…"
                armedDelete -> "Press again: delete $what ($size) $forGood"
                else -> "Delete all $what"
            },
            leading = { TrashMark() },
        ) {
            if (deleting) return@Button
            if (!armedDelete) {
                armedDelete = true
            } else {
                armedDelete = false
                deleting = true
                scope.launch {
                    val r = attempt { Jdvr.backend.deleteShowRecordings(seriesId, dryRun = false) }
                    deleting = false
                    if (r == null) {
                        onNote("Couldn't delete them.")
                    } else {
                        onNote("Deleted ${r.deleted} recording${if (r.deleted == 1) "" else "s"}" + (if (r.trashed && trashDays != null) " to the trash" else "") + if (r.skipped > 0) " (${r.skipped} left: recording now, or not yours to delete)" else "")
                        onDeleted()
                    }
                }
            }
        }
    }
    val ad = archived
    if (ad != null && ad.count > 0) {
        // Files, not episodes: an episode can have several (the pieces of an interrupted recording).
        val what = "${ad.count} archived episode file${if (ad.count == 1) "" else "s"}"
        val size = "%.1f GB".format(ad.bytes / (1024.0 * 1024 * 1024))
        val from = if (ad.places.isEmpty()) "the archive" else ad.places.joinToString(", ")
        Button(
            when {
                deleting -> "Deleting…"
                armedArchived -> "Press again: delete $what ($size) from $from $forGood"
                else -> "Delete all $what"
            },
            leading = { TrashMark() },
        ) {
            if (deleting) return@Button
            if (!armedArchived) {
                armedArchived = true
            } else {
                armedArchived = false
                deleting = true
                scope.launch {
                    val r = attempt { Jdvr.backend.deleteShowRecordings(seriesId, dryRun = false, archive = true) }
                    deleting = false
                    if (r == null) {
                        onNote("Couldn't delete them.")
                    } else {
                        onNote("Deleted ${r.deleted} archived episode file${if (r.deleted == 1) "" else "s"}" + (if (r.trashed && trashDays != null) " to the trash" else "") + if (r.skipped > 0) " (${r.skipped} left: not yours to delete)" else "")
                        onDeleted()
                    }
                }
            }
        }
    }
}

/** Days a delete stays in Episode History's trash; null when deletes are for good. */
@Composable
fun rememberTrashDays(): Int? {
    val days by androidx.compose.runtime.produceState<Int?>(null) { value = attempt { Jdvr.backend.trashDays() } }
    return days
}

fun daysText(days: Int): String = if (days == 1) "1 day" else "$days days"

/** Bumped when a recording's commercial marks change, so the details page's Commercials box reads them again. */
object CommercialMarks {
    var changed by mutableIntStateOf(0)
}
