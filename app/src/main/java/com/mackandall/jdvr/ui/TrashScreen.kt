package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private fun utc(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

private fun local(i: Instant): LocalDateTime = LocalDateTime.ofInstant(i, ZoneId.systemDefault())

private fun gb(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "${maxOf(1, bytes / (1024 * 1024))} MB"

/** "3 h ago", "yesterday 9:40 PM", or the day and time. */
private fun ago(i: Instant): String {
    val d = Duration.between(i, Instant.now())
    return when {
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 12 -> "${d.toHours()} h ago"
        else -> local(i).let { "${it.day()} ${it.time()}" }
    }
}

/** "in 1 day 22 h", "in 3 h", "soon". */
private fun until(i: Instant): String {
    val d = Duration.between(Instant.now(), i)
    return when {
        d.toMinutes() < 5 -> "soon"
        d.toHours() < 1 -> "in ${d.toMinutes()} min"
        d.toDays() < 1 -> "in ${d.toHours()} h"
        else -> "in ${daysText(d.toDays().toInt())}" + (d.toHours() % 24).let { if (it > 0) " $it h" else "" }
    }
}

/**
 * Episode History's trash: what was deleted, newest first, until it goes for good. OK on one
 * offers Restore (puts it back in its library) and, for administrators, Delete now.
 */
@Composable
fun TrashScreen(entry: FocusRequester) {
    var trash by remember { mutableStateOf<LiveTvRepository.TrashList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        trash = Jdvr.liveTv.trash()
        loaded = true
    }
    // An entry goes by itself when its time is up: the list follows.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            reload++
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        val t = trash
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Trash", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            if (t != null && t.items.isNotEmpty()) {
                Text("${t.items.size} item${if (t.items.size == 1) "" else "s"} · ${gb(t.bytes)}", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            note?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        if (t != null) {
            Text(
                if (!t.enabled) "The trash is off on the server: deletes are for good."
                else "Deleted recordings stay here ${daysText(t.keepDays)}" + (if (t.minFreeGb > 0) ", or until a drive has under ${t.minFreeGb} GB free" else "") + ".",
                color = Jc.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(12)
        when {
            !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 16.sp)
            t == null -> Text("Couldn't load the trash (it needs Episode History on the server).", color = Jc.Amber, fontSize = 16.sp)
            t.items.isEmpty() -> Text("The trash is empty.", color = Jc.Muted, fontSize = 16.sp, modifier = Modifier.focusRequester(entry))
            else -> {
                if (t.canEmpty) {
                    var armed by remember { mutableStateOf(false) }
                    LaunchedEffect(armed) {
                        if (armed) {
                            delay(4000)
                            armed = false
                        }
                    }
                    Button(if (armed) "Press again: delete all ${t.items.size} for good (${gb(t.bytes)})" else "Empty the trash", leading = { TrashMark() }) {
                        if (!armed) {
                            armed = true
                        } else {
                            armed = false
                            scope.launch {
                                val n = runCatching { Jdvr.liveTv.emptyTrash() }.getOrNull()
                                note = if (n == null) "Couldn't empty it." else "Deleted $n for good."
                                reload++
                            }
                        }
                    }
                    Spacer(10)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(t.items, key = { it.id }) { e ->
                        TrashRow(
                            e = e,
                            expanded = open == e.id,
                            canRestore = t.canRestore,
                            canDelete = t.canEmpty,
                            modifier = if (e == t.items.first()) Modifier.focusRequester(entry) else Modifier,
                            onToggle = { open = if (open == e.id) null else e.id },
                            onRestore = {
                                scope.launch {
                                    val ok = runCatching { Jdvr.liveTv.restoreFromTrash(e.id) }.getOrDefault(false)
                                    note = if (ok) "Put back: ${e.title}. It's in its library again in a minute or so." else "Couldn't put it back: something may already be where it was."
                                    open = null
                                    reload++
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    val ok = runCatching { Jdvr.liveTv.deleteFromTrash(e.id) }.getOrDefault(false)
                                    note = if (ok) "Deleted for good: ${e.title}." else "Couldn't delete it."
                                    open = null
                                    reload++
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashRow(
    e: LiveTvRepository.TrashItem,
    expanded: Boolean,
    canRestore: Boolean,
    canDelete: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleted = utc(e.deletedUtc)
    val expires = utc(e.expiresUtc)
    Column {
        Surface(
            onClick = onToggle,
            modifier = modifier.fillMaxWidth().height(64.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title + (e.detail?.let { " — $it" } ?: ""),
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            deleted?.let { "Deleted ${ago(it)}" + (e.deletedBy?.let { b -> " by $b" } ?: "") },
                            gb(e.bytes),
                            e.place?.let { "from $it" },
                        ).joinToString(" · "),
                        color = Jc.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                expires?.let { Chip("Gone ${until(it)}", Jc.Muted) }
            }
        }
        if (expanded) {
            var armed by remember(e.id) { mutableStateOf(false) }
            // Opening a row puts the remote on its first button, not wherever Down would land.
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            LaunchedEffect(armed) {
                if (armed) {
                    delay(4000)
                    armed = false
                }
            }
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canRestore) Button("Restore", leading = { Text("↩", color = Jc.Text) }, modifier = Modifier.focusRequester(first), onClick = onRestore)
                if (canDelete) {
                    Button(if (armed) "Press again: delete for good" else "Delete now", leading = { TrashMark() }, modifier = if (canRestore) Modifier else Modifier.focusRequester(first)) {
                        if (!armed) armed = true else onDelete()
                    }
                }
                Button("Keep it in the trash", modifier = if (canRestore || canDelete) Modifier else Modifier.focusRequester(first), onClick = onToggle)
            }
        }
    }
}
