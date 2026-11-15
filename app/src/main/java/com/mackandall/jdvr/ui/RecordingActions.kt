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
import com.mackandall.jdvr.data.Jdvr
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
    var active by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.active(itemId))) }
    LaunchedEffect(itemId) { active = Answers.get(ItemAsk.active(itemId)) }
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
    var active by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.active(itemId))) }
    var check by remember(itemId) { mutableIntStateOf(0) }
    val confirmStop = rememberConfirm<String>(itemId)
    LaunchedEffect(itemId, check) { active = Answers.get(ItemAsk.active(itemId)) }
    val a = active
    if (!rights.record || a == null || !a.canStop) return
    a.recordings.forEach { r ->
        val what = if (a.recordings.size > 1) ": ${r.name}" else ""
        Button(if (confirmStop.armed == r.id) "Press again to stop recording$what" else "Stop recording$what", leading = { Dot(Jc.Recording) }) {
            confirmStop.press(r.id) {
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
    var fav by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.favourite(itemId))) }
    var reload by remember { mutableIntStateOf(0) }
    val confirmDelete = rememberConfirm<Unit>(itemId)
    // Forget needs Episode History; on a server without it the button isn't offered.
    var canForget by remember { mutableStateOf(Answers.peek(ItemAsk.hasEpisodeHistory) == true) }
    LaunchedEffect(Unit) { canForget = Answers.get(ItemAsk.hasEpisodeHistory) == true }
    val confirmUnfav = rememberConfirm<Unit>(itemId)
    val confirmForget = rememberConfirm<Unit>(itemId)
    val confirmRemove = rememberConfirm<Unit>(itemId)
    var commercials by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.commercials(itemId))) }
    var commercialsCheck by remember(itemId) { mutableIntStateOf(0) }
    LaunchedEffect(itemId, commercialsCheck) {
        commercials = Answers.get(ItemAsk.commercials(itemId))
        // A scan in progress: check again every few seconds until it's done.
        val scanned = commercials?.inFlight == true
        while (commercials?.inFlight == true) {
            delay(4000)
            commercials = attempt { Jdvr.backend.commercialStatus(itemId) }
            Answers.put(ItemAsk.commercials(itemId), commercials)
        }
        if (scanned) CommercialMarks.changed++
    }
    LaunchedEffect(itemId, reload) { fav = Answers.get(ItemAsk.favourite(itemId)) }
    // JellyDVR Space: "Move to archive…" for recordings in the live folder. While a move runs it
    // checks back; once the recording has moved, this item is gone (the archive copy is a new
    // item), so the page closes.
    var archive by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.archive(itemId))) }
    var archiveCheck by remember(itemId) { mutableIntStateOf(0) }
    var picking by remember(itemId) { mutableStateOf(false) }
    // Program start: a skip marker to remove, or a trim whose original was kept to undo.
    var start by remember(itemId) { mutableStateOf(Answers.peek(ItemAsk.programStart(itemId))) }
    var startCheck by remember(itemId) { mutableIntStateOf(0) }
    val confirmUndo = rememberConfirm<Unit>(itemId)
    val confirmFind = rememberConfirm<Unit>(itemId)
    val confirmClearStart = rememberConfirm<Unit>(itemId)
    val confirmAudio = rememberConfirm<Unit>(itemId)
    // Comskip's tools change the recording: only for users allowed to edit recordings.
    val edit = editPermission()
    val editTools = tools && edit != null && rights.edit
    LaunchedEffect(itemId, startCheck, editTools) { if (editTools) start = Answers.get(ItemAsk.programStart(itemId)) }
    LaunchedEffect(itemId, archiveCheck, rights.archive) {
        if (!manage || !rights.archive) return@LaunchedEffect
        archive = Answers.get(ItemAsk.archive(itemId))
        var wasMoving = false
        while (archive?.inFlight != null) {
            wasMoving = true
            delay(5000)
            archive = attempt { Jdvr.backend.archiveChoices(itemId) }
            Answers.put(ItemAsk.archive(itemId), archive)
        }
        if (wasMoving && archive == null) {
            onNote("Moved to the archive")
            onDeleted()
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
            Button(if (confirmUnfav.isArmed) "Press again: remove from favourites" else "★ In Recording Favourites") {
                confirmUnfav.press {
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
                Button(if (confirmRemove.isArmed) "Press again: remove commercials" else "Remove commercials", modifier = comModifier) {
                    confirmRemove.press {
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
                !confirmFind.isArmed -> "Find program start"
                edit!!.programStartTrims -> "Press again: find it and cut before it. ${edit.undoNote}"
                else -> "Press again: find it and skip to it"
            },
        ) {
            confirmFind.press {
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
        Button(if (confirmClearStart.isArmed) "Press again: play from the very start again" else "Remove program start (skips to ${s / 60}:${"%02d".format(s % 60)})") {
            confirmClearStart.press {
                scope.launch {
                    onNote(attempt { Jdvr.backend.clearProgramStart(itemId)?.message } ?: "Couldn't remove it.")
                    startCheck++
                }
            }
        }
    }
    if (editTools && cut == null && ps?.canUndo == true) {
        val until = ps.undoUntil?.let { untilDate(it) }?.let { ", until $it" } ?: ""
        Button(if (confirmUndo.isArmed) "Press again: put the original back" else "Undo trim (put the original back$until)") {
            confirmUndo.press {
                scope.launch {
                    onNote(attempt { Jdvr.backend.undoTrim(itemId)?.message } ?: "Couldn't undo it.")
                    startCheck++
                }
            }
        }
    }
    if (editTools && cut == null) {
        Button(if (confirmAudio.isArmed) "Press again: cut off audio in the wrong format. ${edit!!.undoNote}" else "Fix audio format") {
            confirmAudio.press {
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
            if (confirmForget.isArmed) {
                "Press again: delete and forget"
            } else if (fav?.isMovie == true) {
                "Forget this movie"
            } else {
                "Forget this episode"
            },
        ) {
            confirmForget.press {
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
            manage -> if (confirmDelete.isArmed) (if (trashDays != null) "Press again: move to the trash" else "Press again to delete") else "Delete recording"
            confirmDelete.isArmed -> if (trashDays != null) "Press again: move to the trash" else "Press again: delete$from for good"
            else -> "Delete$from"
        }
    if (canDelete && (if (manage) rights.delete else rights.deleteArchive) && cut == null) {
        Button(deleteLabel, leading = { TrashMark() }) {
            confirmDelete.press {
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
    var delete by remember(seriesId) { mutableStateOf(Answers.peek(ItemAsk.showDelete(seriesId, archive = false))) }
    val confirmDelete = rememberConfirm<Unit>(seriesId)
    var deleting by remember(seriesId) { mutableStateOf(false) }
    var archive by remember(seriesId) { mutableStateOf(Answers.peek(ItemAsk.archive(seriesId))) }
    var archiveCheck by remember(seriesId) { mutableIntStateOf(0) }
    var picking by remember(seriesId) { mutableStateOf(false) }
    var forget by remember(seriesId) { mutableStateOf(Answers.peek(ItemAsk.showForget(seriesId))) }
    val confirmForget = rememberConfirm<Unit>(seriesId)
    // The show's archived copies: only on a TV allowed to delete from the archive.
    var archived by remember(seriesId) { mutableStateOf(Answers.peek(ItemAsk.showDelete(seriesId, archive = true))) }
    val confirmArchived = rememberConfirm<Unit>(seriesId)
    val rights = deviceRights()
    // Asked together (one after the other, the buttons came in turn).
    LaunchedEffect(seriesId, rights.delete) { delete = if (rights.delete) Answers.get(ItemAsk.showDelete(seriesId, archive = false)) else null }
    LaunchedEffect(seriesId, rights.deleteArchive) { archived = if (rights.deleteArchive) Answers.get(ItemAsk.showDelete(seriesId, archive = true)) else null }
    LaunchedEffect(seriesId) { forget = Answers.get(ItemAsk.showForget(seriesId)) }
    WatchLiveButtons(seriesId)
    StopRecordingButtons(seriesId, onNote)
    LaunchedEffect(seriesId, archiveCheck, rights.archive) {
        if (!rights.archive) {
            archive = null
            return@LaunchedEffect
        }
        archive = Answers.get(ItemAsk.archive(seriesId))
        // Moves under way: check back until they're done.
        while (archive?.inFlight != null) {
            delay(5000)
            archive = attempt { Jdvr.backend.archiveChoices(seriesId) }
            Answers.put(ItemAsk.archive(seriesId), archive)
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
        Button(if (confirmForget.isArmed) "Press again: forget all of ${f.show ?: "this show"}" else "Forget this show (${f.episodes} episode${if (f.episodes == 1) "" else "s"})") {
            confirmForget.press {
                scope.launch {
                    val r = attempt { Jdvr.backend.forgetShow(seriesId, dryRun = false) }
                    onNote(if (r == null) "Couldn't forget it." else "Forgotten: its episodes can be recorded again")
                    forget = attempt { Jdvr.backend.forgetShow(seriesId, dryRun = true) }
                    Answers.put(ItemAsk.showForget(seriesId), forget)
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
                confirmDelete.isArmed -> "Press again: delete $what ($size) $forGood"
                else -> "Delete all $what"
            },
            leading = { TrashMark() },
        ) {
            if (deleting) return@Button
            confirmDelete.press {
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
                confirmArchived.isArmed -> "Press again: delete $what ($size) from $from $forGood"
                else -> "Delete all $what"
            },
            leading = { TrashMark() },
        ) {
            if (deleting) return@Button
            confirmArchived.press {
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
