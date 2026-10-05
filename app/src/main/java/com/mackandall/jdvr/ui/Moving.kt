package com.mackandall.jdvr.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository.MoveProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val MoveBlue = Color(0xFF00A4DC)

/**
 * Recordings JellyDVR Space is moving to the archive right now (from the TV, the web client or
 * the space manager itself), polled from the server: every 2 s while anything is moving, every
 * 15 s otherwise. Keyed by item id and, for episodes, show id (dashes removed), so a card for
 * either can find it. [finished] counts completed moves, for screens that want to reload.
 */
object MoveWatcher {
    private val _moving = MutableStateFlow<Map<String, MoveProgress>>(emptyMap())
    val moving: StateFlow<Map<String, MoveProgress>> = _moving
    private val _finished = MutableStateFlow(0)
    val finished: StateFlow<Int> = _finished
    private val wake = MutableStateFlow(0)

    /** Check now (a move was just started). */
    fun kick() {
        wake.value++
    }

    /** Runs for the app's lifetime (from the root composable). */
    suspend fun run() {
        while (true) {
            val list = runCatching { Jdvr.liveTv.moving() }.getOrNull().orEmpty()
            val map = HashMap<String, MoveProgress>()
            list.forEach { m ->
                map[m.itemId.plain()] = m
                m.seriesId?.let { map.putIfAbsent(it.plain(), m) }
            }
            val before = _moving.value.values.map { it.itemId }.toSet()
            if (before.any { id -> list.none { it.itemId == id } }) _finished.value++
            _moving.value = map
            val seen = wake.value
            val wait = if (list.isEmpty()) 15_000L else 2_000L
            var waited = 0L
            while (waited < wait && wake.value == seen) {
                delay(250)
                waited += 250
            }
        }
    }

    private fun String.plain() = replace("-", "").lowercase()
}

/** The move of this item (or one of this show's recordings), if one is under way. */
@Composable
fun movingFor(id: String): MoveProgress? {
    val moving by MoveWatcher.moving.collectAsState()
    return moving[id.replace("-", "").lowercase()]
}

@Composable
fun MoveWatcherEffect() {
    LaunchedEffect(Unit) { MoveWatcher.run() }
}

/**
 * Over a card whose recording is being moved: blue stripes drifting across a dimmed poster,
 * "Moving" with the percent, and a progress strip along the bottom.
 */
@Composable
fun BoxScope.MovingOverlay(p: MoveProgress) {
    val shift by rememberInfiniteTransition(label = "moving").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "stripes",
    )
    val fraction by animateFloatAsState(p.fraction, tween(1500), label = "progress")
    Canvas(Modifier.matchParentSize()) {
        drawRect(Color.Black.copy(alpha = 0.45f))
        val gap = 28.dp.toPx()
        val band = 10.dp.toPx()
        val offset = shift * gap
        clipRect {
            var x = -size.height - gap + offset
            while (x < size.width + gap) {
                val path = Path().apply {
                    moveTo(x, size.height)
                    lineTo(x + band, size.height)
                    lineTo(x + band + size.height, 0f)
                    lineTo(x + size.height, 0f)
                    close()
                }
                drawPath(path, MoveBlue.copy(alpha = 0.22f))
                x += gap
            }
        }
    }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⇢", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            if (p.started) "Moving ${(p.fraction * 100).toInt()}%" else "Waiting to move",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.6f))) {
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(MoveBlue))
    }
}

/**
 * On the details page in place of the "Move to archive…" button: an animated bar (stripes
 * sliding through the filled part) with the percent and how much is copied.
 */
@Composable
fun MovingBar(p: MoveProgress?) {
    val shift by rememberInfiniteTransition(label = "movingBar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "stripes",
    )
    val fraction by animateFloatAsState(p?.fraction ?: 0f, tween(1500), label = "progress")
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Jc.Panel2).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    p == null || !p.started -> "Waiting to move to the archive…"
                    else -> "Moving to the archive · ${(p.fraction * 100).toInt()}%"
                },
                color = Jc.Text,
                fontSize = 16.sp,
            )
            Canvas(Modifier.width(260.dp).height(8.dp).clip(RoundedCornerShape(4.dp))) {
                drawRect(Jc.Text.copy(alpha = 0.12f))
                val filled = size.width * fraction
                drawRect(MoveBlue, size = androidx.compose.ui.geometry.Size(filled, size.height))
                val gap = 16.dp.toPx()
                clipRect(right = filled) {
                    var x = -gap + shift * gap
                    while (x < filled) {
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 4.dp.toPx())
                        x += gap
                    }
                }
            }
            p?.let {
                if (it.started) Text("${gb(it.copiedBytes)} of ${gb(it.bytes)}", color = Jc.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** A pulsing "Moving 42%" pill, for rows that show badges rather than a picture. */
@Composable
fun MovingTag(p: MoveProgress) {
    val pulse by rememberInfiniteTransition(label = "movingTag").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Text(
        if (p.started) "⇢ Moving ${(p.fraction * 100).toInt()}%" else "⇢ Waiting to move",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.background(MoveBlue.copy(alpha = pulse), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun gb(bytes: Long): String = "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
