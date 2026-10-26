package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.ShowStorage
import com.mackandall.jdvr.data.StorageFolder
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.launch
import java.util.UUID

private const val GB = 1024.0 * 1024 * 1024

private fun size(bytes: Long): String =
    (bytes / GB).let { if (it >= 1000) "%.1f TB".format(it / 1024) else if (it >= 100) "%.0f GB".format(it) else "%.1f GB".format(it) }

private fun floor(gb: Int): String = if (gb >= 1024) "%.1f TB".format(gb / 1024.0) else "$gb GB"

/**
 * The folder the show of guide programme [p] is stored in ("USBShareD / ArchivedTV"), or null:
 * from what was read lately, else asked once the cursor has rested on it.
 */
@Composable
fun rememberStoredIn(
    programId: UUID,
    show: String,
    isMovie: Boolean,
): String? {
    val rights = deviceRights()
    var stored by remember(programId) { mutableStateOf(com.mackandall.jdvr.data.ShowStorageCache.fresh(show)?.summary()) }
    LaunchedEffect(programId, rights.archive) {
        if (!rights.archive || isMovie || show.isBlank()) {
            stored = null
            return@LaunchedEffect
        }
        com.mackandall.jdvr.data.ShowStorageCache.fresh(show)?.let {
            stored = it.summary()
            return@LaunchedEffect
        }
        // Moving along the guide: only ask about the programme it stops on.
        kotlinx.coroutines.delay(700)
        stored = attempt { Jdvr.backend.showStorage(programId) }?.summary()
    }
    return stored
}

/** What the show's storage comes to, in a few words: "USBShareD / ArchivedTV, then …", or null when it stays put. */
fun ShowStorage.summary(): String? = labels.takeIf { it.isNotEmpty() }?.joinToString(", then ")

/**
 * "Store this show in…" (JellyDVR Space) for the show of [itemId]: a button showing the show's
 * folders, opening [ShowStoragePicker]. Shown only to TVs allowed to move recordings to the
 * archive, and not for movies.
 */
@Composable
fun StoreShowButton(
    itemId: UUID,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    onChanged: (ShowStorage?) -> Unit = {},
) {
    val rights = deviceRights()
    var storage by remember(itemId) { mutableStateOf<ShowStorage?>(null) }
    var picking by remember(itemId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(itemId, rights.archive) {
        storage = if (rights.archive) attempt { Jdvr.backend.showStorage(itemId) } else null
    }
    val s = storage ?: return
    if (s.problem != null || s.folders.isEmpty()) return
    Button(s.summary()?.let { "Store this show in… · $it" } ?: "Store this show in…", modifier = modifier) {
        scope.launch {
            // Ask again: free space may have changed since the menu opened.
            storage = attempt { Jdvr.backend.showStorage(itemId) } ?: storage
            picking = true
        }
    }
    if (picking) {
        ShowStoragePicker(
            s,
            onSaved = { locations, message ->
                picking = false
                onNote(message)
                scope.launch {
                    storage = attempt { Jdvr.backend.showStorage(itemId) } ?: s.copy(locations = locations)
                    onChanged(storage)
                }
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The show's storage folders, in order (finished recordings go to the first with room), and the
 * other storage folders to add. OK on a chosen folder: try it first or later, or take it out.
 * Folders themselves are set up on the web page. [onSaved] gets the new list and what to tell
 * the viewer.
 */
@Composable
fun ShowStoragePicker(
    storage: ShowStorage,
    onSaved: (List<String>, String) -> Unit,
    onDismiss: () -> Unit,
    // Instead of saving: hand the list back (the Record series dialog saves it with the series).
    onPicked: ((List<String>) -> Unit)? = null,
) {
    var chosen by remember { mutableStateOf(storage.locations) }
    var open by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val byPath = storage.folders.associateBy { it.path }
    // Movie-only folders aren't for shows (one already chosen still shows, to take it out).
    val others = storage.folders.filter { it.path !in chosen && it.kind != "movies" }

    LaunchedEffect(Unit) { first.focusSoon() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides false) {
            Column(
                Modifier
                    .width(760.dp)
                    .heightIn(max = 500.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Jc.Sheet)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Store this show in", fontSize = 26.sp, color = Jc.Title, fontWeight = FontWeight.SemiBold)
                Text("${storage.show} · each finished recording goes to the first folder with room", fontSize = 15.sp, color = Jc.Muted)
                if (storage.sortMode != "active") {
                    Text(
                        "Sorting is ${if (storage.sortMode == "off") "off" else "in Preview"} on the server (JellyDVR Space settings), so nothing moves until it's Active.",
                        fontSize = 14.sp,
                        color = Jc.Amber,
                    )
                }

                Caption(if (chosen.isEmpty()) "IN THIS ORDER · none: it stays on the recording drive" else "IN THIS ORDER")
                chosen.forEachIndexed { i, path ->
                    val f = byPath[path] ?: StorageFolder(path = path, label = path, problem = "no longer a storage folder")
                    FolderRow(
                        f,
                        number = "${i + 1}",
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { open = if (open == path) null else path }
                    if (open == path) {
                        Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (i > 0) {
                                Button("▲ Try first") {
                                    chosen = listOf(path) + (chosen - path)
                                    open = null
                                }
                            }
                            if (i < chosen.size - 1) {
                                Button("▼ Try later") {
                                    chosen = chosen.toMutableList().also { it.removeAt(i); it.add(i + 1, path) }
                                    open = null
                                }
                            }
                            Button("Take out") {
                                chosen = chosen - path
                                open = null
                            }
                        }
                    }
                }

                if (others.isNotEmpty()) {
                    Caption("ADD A FOLDER")
                    others.forEachIndexed { i, f ->
                        FolderRow(
                            f,
                            number = "+",
                            dashed = true,
                            modifier = if (chosen.isEmpty() && i == 0) Modifier.focusRequester(first) else Modifier,
                        ) { if (f.problem == null) chosen = chosen + f.path }
                    }
                }

                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(if (saving) "Saving…" else "Save", primary = true) {
                        if (saving) return@Button
                        onPicked?.let {
                            it(chosen)
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            val r = attempt { Jdvr.backend.setShowStorage(storage.show, chosen) }
                            if (r == null) {
                                problem = "Couldn't save it."
                                saving = false
                            } else {
                                onSaved(chosen, r.message)
                            }
                        }
                    }
                    if (chosen.isNotEmpty()) Button("Keep on the recording drive") { chosen = emptyList() }
                    Button("Cancel", onClick = onDismiss)
                }
                // Beside the buttons, where the eye is after pressing Save.
                problem?.let { Text(it, fontSize = 15.sp, color = Jc.Amber) }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 13.sp, color = Jc.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun FolderRow(
    f: StorageFolder,
    number: String,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val usable = f.problem == null
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (dashed) Color.Transparent else Jc.Panel2,
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = Jc.Text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (dashed) Border(BorderStroke(1.dp, Jc.Muted.copy(alpha = 0.4f))) else Border.None,
                focusedBorder = Border(BorderStroke(2.dp, if (usable) Jc.Focus else Jc.Muted)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (dashed) Jc.Muted else Jc.Focus, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Jc.Text.copy(alpha = if (usable) 1f else 0.55f))
                Text(
                    (if (f.kind == "movies") "takes movies · " else "") + "keeps ${floor(f.keepFreeGb)} free",
                    fontSize = 13.sp,
                    color = Jc.Muted,
                )
                f.problem?.let { Text(it, fontSize = 13.sp, color = Jc.Recording) }
            }
            Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val free = f.freeBytes
                val total = f.totalBytes
                if (free != null && total != null && total > 0) FolderBar(free, total, f.keepFreeGb)
                if (free != null) Text("${size(free)} free" + (total?.let { " of ${size(it)}" } ?: ""), fontSize = 13.sp, color = Jc.Muted)
            }
        }
    }
}

// Used space, and the folder's floor as an amber tick (as in "Move to archive…").
@Composable
private fun FolderBar(
    free: Long,
    total: Long,
    keepFreeGb: Int,
) {
    val track = Jc.Text.copy(alpha = 0.12f)
    val used = Color(0xFF64748B)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        fun x(b: Double) = (b / total).coerceIn(0.0, 1.0).toFloat() * size.width
        drawRect(track)
        drawRect(used, size = Size(x((total - free).toDouble()), size.height))
        if (keepFreeGb > 0) {
            drawRect(Jc.Amber, topLeft = Offset(x(total - keepFreeGb * GB) - 1f, 0f), size = Size(2f, size.height))
        }
    }
}
