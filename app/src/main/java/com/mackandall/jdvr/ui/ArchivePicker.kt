package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.LiveTvRepository.ArchiveChoice
import com.mackandall.jdvr.data.LiveTvRepository.ArchiveChoices

private const val GB = 1024.0 * 1024 * 1024
private val UsedColour = Color(0xFF64748B)
private val MineColour = Color(0xFF00A4DC)
private val KindLabels = mapOf("tv" to "TV", "movies" to "Movies", "sports" to "Sports", "both" to "TV and movies")

private fun gb(bytes: Long): String = (bytes / GB).let { if (it >= 100) "%.0f GB".format(it) else "%.1f GB".format(it) }

/**
 * JellyDVR Space's "Move to archive…", as in the web client's item menu: the archive locations
 * that take this recording's kind, each with a bar of its space (used, this recording, free;
 * the tick is the location's keep-free floor). Back closes it.
 */
@Composable
fun ArchivePicker(choices: ArchiveChoices, onPick: (ArchiveChoice) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val usable = choices.targets.map { it.problem == null && choices.blocked == null }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Jc.Sheet)
                .onFocusChanged { hasFocus = it.hasFocus }
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Move to archive", fontSize = 26.sp, color = Jc.Text)
            Text(
                "${choices.name} · ${KindLabels[choices.kind] ?: choices.kind} · ${gb(choices.bytes)}",
                fontSize = 16.sp,
                color = Jc.Muted,
            )
            if (choices.targets.any { it.totalBytes != null }) Legend()
            choices.blocked?.let { Text("It can't be moved while it's $it.", fontSize = 16.sp, color = Jc.Amber) }
            if (choices.targets.isEmpty()) {
                Text(
                    "No archive location takes ${(KindLabels[choices.kind] ?: choices.kind).lowercase()} recordings yet. " +
                        "Add one in the web dashboard: Plugins › JellyDVR Space › Archive storage.",
                    fontSize = 16.sp,
                    color = Jc.Text,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(choices.targets.indices.toList()) { i ->
                    val t = choices.targets[i]
                    val focus = if (i == usable.indexOf(true)) Modifier.focusRequester(first) else Modifier
                    Destination(t, choices.bytes, usable[i], focus) { onPick(t) }
                }
                item {
                    val cancelFocus = if (usable.none { it }) Modifier.focusRequester(first) else Modifier
                    Button("Cancel", modifier = cancelFocus.padding(top = 4.dp), onClick = onDismiss)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // The dialog's window and list need a frame or two before anything in them can take
        // focus, and an early requestFocus silently does nothing: retry until it lands.
        repeat(40) {
            if (hasFocus) return@LaunchedEffect
            runCatching { first.requestFocus() }
            kotlinx.coroutines.delay(50)
        }
    }
}

@Composable
private fun Destination(t: ArchiveChoice, bytes: Long, usable: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = { if (usable) onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Jc.Panel2,
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, if (usable) Jc.Focus else Jc.Muted))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val dim = if (usable) 1f else 0.55f
            Text(t.path, fontSize = 18.sp, color = Jc.Text.copy(alpha = dim))
            val free = t.freeBytes
            Text(
                (KindLabels[t.kind] ?: t.kind) +
                    (if (free != null) " · ${gb(free)} free" + (t.totalBytes?.let { " of ${gb(it)}" } ?: "") else "") +
                    (if (free != null && free >= bytes) " · ${gb(free - bytes)} after" else ""),
                fontSize = 14.sp,
                color = Jc.Muted.copy(alpha = dim),
            )
            if (free != null && t.totalBytes != null && t.totalBytes > 0) SpaceBar(t, free, t.totalBytes, bytes)
            when {
                t.problem != null -> Text(t.problem, fontSize = 14.sp, color = Jc.Recording)
                else -> {
                    Text("→ ${t.destination}", fontSize = 13.sp, color = Jc.Muted, maxLines = 1)
                    t.note?.let { Text(it, fontSize = 14.sp, color = Jc.Amber) }
                }
            }
        }
    }
}

@Composable
private fun SpaceBar(t: ArchiveChoice, free: Long, total: Long, bytes: Long) {
    val fits = free >= bytes
    val belowFloor = fits && free - bytes < t.keepFreeGb * GB
    val mineColour = if (!fits) Jc.Recording else if (belowFloor) Jc.Amber else MineColour
    val track = Jc.Text.copy(alpha = 0.12f)
    Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(10.dp).clip(RoundedCornerShape(5.dp))) {
        fun x(b: Double) = (b / total).coerceIn(0.0, 1.0).toFloat() * size.width
        val used = (total - free).toDouble()
        val mine = (if (fits) bytes else free).toDouble()
        drawRect(track)
        drawRect(UsedColour, size = Size(x(used), size.height))
        drawRect(mineColour, topLeft = Offset(x(used), 0f), size = Size(maxOf(x(used + mine) - x(used), if (mine > 0) 3f else 0f), size.height))
        if (t.keepFreeGb > 0) {
            val fx = x(total - t.keepFreeGb * GB)
            drawRect(Color.White.copy(alpha = 0.7f), topLeft = Offset(fx - 1f, 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(UsedColour to "used", MineColour to "this recording", Jc.Text.copy(alpha = 0.12f) to "free").forEach { (c, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text(label, fontSize = 13.sp, color = Jc.Muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(2.dp).height(13.dp).background(Color.White.copy(alpha = 0.7f)))
            Text("keep-free floor", fontSize = 13.sp, color = Jc.Muted)
        }
    }
}
