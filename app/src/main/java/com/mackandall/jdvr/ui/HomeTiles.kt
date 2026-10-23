package com.mackandall.jdvr.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Sky
import com.mackandall.jdvr.data.WeatherNow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Home's tiles (Settings › Home): the weather, lists that scroll inside their tile (what's
 * recording, what records next, what needs attention), and the tuners with the recordings drive.
 * A tile's size comes from Home's layout; its lists show as many rows as fit and scroll with ▲▼
 * (focus moves on to the next tile at either end).
 */

/** A tile's share of its row (or a row's of the screen), eased to its new size when tiles come and go. */
@Composable
fun animatedWeight(target: Float): Float =
    animateFloatAsState(target, tween(450, easing = FastOutSlowInEasing), label = "homeWeight").value

@Composable
fun HomeTileFrame(
    modifier: Modifier,
    background: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(background, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun TileLabel(
    text: String,
    trailing: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Jc.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, color = Jc.Muted, fontSize = 12.sp) }
    }
}

// ---- weather -----------------------------------------------------------------------------------

/** The sky as a small moving picture: sun rays turning, clouds drifting, rain, snow, a flash. */
@Composable
fun WeatherIcon(
    sky: Sky,
    isDay: Boolean,
    size: Dp,
) {
    val t = rememberInfiniteTransition(label = "weather")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    val drift by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val fall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "fall")
    val slow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "snow")
    val flash by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4500, easing = LinearEasing)), label = "flash")
    val sun = Color(0xFFE9A21B)
    val cloud = if (Jc.Light) Color.White else Color(0xFFC7D2DE)
    val cloudLine = Jc.Muted
    val rain = if (Jc.Light) Color(0xFF2F6FA0) else Color(0xFF5CC8EF)
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 120f
        fun sunAt(cx: Float, cy: Float, r: Float) {
            if (isDay) {
                rotate(spin, Offset(cx * u, cy * u)) {
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        drawLine(sun, Offset((cx + cos(a).toFloat() * (r + 7)) * u, (cy + sin(a).toFloat() * (r + 7)) * u), Offset((cx + cos(a).toFloat() * (r + 14)) * u, (cy + sin(a).toFloat() * (r + 14)) * u), 4.5f * u, StrokeCap.Round)
                    }
                }
                drawCircle(sun, r * u, Offset(cx * u, cy * u))
            } else {
                // A crescent moon by night: a cut-out shape, so it suits any theme and the see-through tile.
                fun disc(x: Float, y: Float) = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(x * u, y * u), r * u)) }
                drawPath(Path.combine(PathOperation.Difference, disc(cx, cy), disc(cx + r * 0.55f, cy - r * 0.35f)), Color(0xFFE6E0C8))
            }
        }
        fun cloudAt(dx: Float) {
            // Bubbles joined into one shape, so the outline goes round the cloud, not each bubble.
            fun part(r: androidx.compose.ui.geometry.Rect, oval: Boolean = true) = Path().apply { if (oval) addOval(r) else addRect(r) }
            var path = part(androidx.compose.ui.geometry.Rect((30 + dx) * u, 52 * u, (62 + dx) * u, 84 * u))
            listOf(
                part(androidx.compose.ui.geometry.Rect((44 + dx) * u, 34 * u, (86 + dx) * u, 76 * u)),
                part(androidx.compose.ui.geometry.Rect((70 + dx) * u, 50 * u, (100 + dx) * u, 82 * u)),
                part(androidx.compose.ui.geometry.Rect((46 + dx) * u, 64 * u, (86 + dx) * u, 84 * u), oval = false),
            ).forEach { path = Path.combine(androidx.compose.ui.graphics.PathOperation.Union, path, it) }
            drawPath(path, cloud)
            drawPath(path, cloudLine.copy(alpha = 0.55f), style = Stroke(2.5f * u))
        }
        fun drops(color: Color) {
            for (i in 0 until 4) {
                val p = (fall + i * 0.27f) % 1f
                val x = 44f + i * 14f - p * 3f
                val y = 86f + p * 26f
                drawLine(color.copy(alpha = 1f - p), Offset(x * u, y * u), Offset((x - 3) * u, (y + 9) * u), 4f * u, StrokeCap.Round)
            }
        }
        val dx = drift * 5f
        when (sky) {
            Sky.Clear -> sunAt(60f, 56f, 20f)
            Sky.PartlyCloudy -> {
                sunAt(44f, 40f, 14f)
                cloudAt(dx)
            }
            Sky.Cloudy -> cloudAt(dx)
            Sky.Fog -> {
                cloudAt(dx * 0.5f)
                for (i in 0 until 3) drawLine(cloudLine.copy(alpha = 0.7f), Offset((26 + dx * (i + 1)) * u, (92 + i * 9) * u), Offset((96 + dx * (i + 1)) * u, (92 + i * 9) * u), 4f * u, StrokeCap.Round)
            }
            Sky.Rain -> {
                cloudAt(dx)
                drops(rain)
            }
            Sky.Storm -> {
                cloudAt(dx)
                drops(rain)
                // A flash every few seconds.
                val a = if (flash in 0.86f..0.9f || flash in 0.92f..0.95f) 1f else 0f
                if (a > 0f) bolt(sun, u)
            }
            Sky.Snow -> {
                cloudAt(dx)
                for (i in 0 until 3) {
                    val p = (slow + i * 0.33f) % 1f
                    drawCircle(rain.copy(alpha = 1f - p), 3.5f * u, Offset((46 + i * 16 + p * 7) * u, (88 + p * 26) * u))
                }
            }
        }
    }
}

private fun DrawScope.bolt(
    color: Color,
    u: Float,
) {
    val path =
        Path().apply {
            moveTo(62 * u, 84 * u)
            lineTo(53 * u, 100 * u)
            lineTo(63 * u, 100 * u)
            lineTo(57 * u, 114 * u)
            lineTo(73 * u, 94 * u)
            lineTo(63 * u, 94 * u)
            lineTo(70 * u, 84 * u)
            close()
        }
    drawPath(path, color)
}

@Composable
fun WeatherTile(
    w: WeatherNow?,
    place: String?,
    modifier: Modifier,
    background: Color,
    cell: Color,
) {
    HomeTileFrame(modifier, background) {
        if (w == null) {
            TileLabel("WEATHER")
            Text(if (place == null) "Set your town in Settings › Home." else "Loading…", color = Jc.Muted, fontSize = 14.sp)
            return@HomeTileFrame
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WeatherIcon(w.sky, w.isDay, 64.dp)
            Column {
                Text("${w.temperature}°", color = Jc.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Text(w.label, color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val hl = if (w.high != null && w.low != null) "H ${w.high}° · L ${w.low}°" else null
                Text(listOfNotNull(hl, place).joinToString(" · "), color = Jc.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (w.hours.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                w.hours.forEach { h ->
                    Column(
                        Modifier.weight(1f).background(cell, RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(h.time.time(), color = Jc.Muted, fontSize = 11.sp, maxLines = 1)
                        Text("${h.temperature}°", color = Jc.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---- lists that scroll inside a tile -----------------------------------------------------------

/** One line of a list tile. */
class TileLine(
    val key: String,
    val title: String,
    val detail: String?,
    val side: String?,
    // The dot before it: none, the recording red, or an amber "look at this".
    val dot: Color?,
    val onClick: () -> Unit,
)

private val LineHeight = 38.dp
private val LineGap = 4.dp

/**
 * A tile holding a list: as many lines as fit, the rest a ▲▼ away (the lines are focusable, so the
 * list scrolls to the focused one). The label says where the window is ("1–3 of 5").
 */
@Composable
fun ListTile(
    label: String,
    lines: List<TileLine>,
    empty: String,
    modifier: Modifier,
    background: Color,
    line: Color,
    // Home's entry focus, on the first line, when this is where Home starts.
    firstModifier: Modifier = Modifier,
    // First in its row: ◄ from its lines opens the menu (focus search would jump to a poster below).
    leftmost: Boolean = false,
) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val stride = with(density) { (LineHeight + LineGap).toPx() }
    val gap = with(density) { LineGap.toPx() }
    val pad = with(density) { 4.dp.toPx() }
    val fits = if (stride > 0) ((viewport - pad + gap) / stride).toInt().coerceAtLeast(1) else 1
    val window = with(density) { (fits * stride - gap + pad).toDp() }
    val first = if (stride > 0) (scroll.value / stride).let { kotlin.math.round(it).toInt() } else 0
    val counter =
        when {
            lines.isEmpty() -> null
            scroll.maxValue == 0 || lines.size <= fits -> "${lines.size}"
            else -> "${first + 1}–${(first + fits).coerceAtMost(lines.size)} of ${lines.size} · ▲▼"
        }
    HomeTileFrame(modifier, background) {
        TileLabel(label, counter)
        if (lines.isEmpty()) {
            Text(empty, color = Jc.Muted, fontSize = 14.sp)
        } else {
            // The room there is, measured; the list itself only as tall as its whole lines.
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it.height }) {
                ScrollingLines(lines, scroll, Modifier.fillMaxWidth().height(window), line, firstModifier, leftmost)
            }
        }
    }
}

@Composable
private fun ScrollingLines(
    lines: List<TileLine>,
    scroll: ScrollState,
    modifier: Modifier,
    line: Color,
    firstModifier: Modifier,
    leftmost: Boolean,
) {
    Column(modifier.verticalScroll(scroll).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(LineGap)) {
        lines.forEachIndexed { i, l ->
            androidx.compose.runtime.key(l.key) {
                Surface(
                    onClick = l.onClick,
                    modifier =
                        (if (i == 0) firstModifier else Modifier)
                            .then(if (leftmost) Modifier.focusProperties { left = androidx.compose.ui.focus.FocusRequester.Cancel } else Modifier)
                            .fillMaxWidth()
                            .height(LineHeight),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = line, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        l.dot?.let { Box(Modifier.size(8.dp).background(it, RoundedCornerShape(50))) }
                        Column(Modifier.weight(1f)) {
                            Text(l.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            l.detail?.let { Text(it, color = Jc.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        l.side?.let { Text(it, color = Jc.Muted, fontSize = 12.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

// ---- tuners and the recordings drive -----------------------------------------------------------

@Composable
fun TunersTile(
    tuners: LiveTvRepository.TunerStatus?,
    drive: LiveTvRepository.Drive?,
    modifier: Modifier,
    background: Color,
    cell: Color,
    // The first answer is in: until then, no tuners means "not yet".
    loaded: Boolean = true,
    // Channel numbers busy only with a live TV buffer: a faint dot, not the red of a recording.
    bufferChannels: Set<String> = emptySet(),
) {
    HomeTileFrame(modifier, background) {
        val t = tuners
        val busy = t?.busy.orEmpty()
        TileLabel("TUNERS", t?.takeIf { it.capacity > 0 }?.let { "${busy.size} of ${it.capacity} in use" })
        if (t == null || t.capacity <= 0) {
            Text(if (loaded) "Not known on this server." else "Loading…", color = Jc.Muted, fontSize = 13.sp)
        } else {
            // Two to a row, busy ones first; more than four tuners: the count above says it.
            val cells = (busy.map { b -> (b.recording.firstOrNull() ?: b.watching.firstOrNull() ?: b.channelName) to
                    when {
                        b.recording.isEmpty() -> Jc.Focus
                        b.number in bufferChannels -> Jc.Recording.copy(alpha = 0.4f)
                        else -> Jc.Recording
                    } } +
                List((t.capacity - busy.size).coerceAtLeast(0)) { "Free" to Color.Unspecified }).take(4)
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { (what, dot) ->
                        Row(
                            Modifier.weight(1f).height(26.dp).background(cell, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val free = what == "Free"
                            if (!free) Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(50)))
                            Text(what, color = if (free) Jc.Muted else Jc.Text, fontSize = 12.sp, fontWeight = if (free) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.weight(1f))
        drive?.takeIf { it.totalBytes > 0 }?.let { d ->
            val gb = 1_000_000_000.0
            val used = (1f - d.freeBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f)
            // About 2.5 GB an hour for an HD recording (what this server's recordings average).
            val hours = (d.freeBytes / gb / 2.5).toInt()
            // One line under the tuners: what's free, and a thin bar of what's used.
            Text(
                String.format(java.util.Locale.US, "Drive · %.0f GB free · about %d h of HD", d.freeBytes / gb, hours),
                color = Jc.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.fillMaxWidth().height(5.dp).background(Jc.Line, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(used).background(if (used > 0.95f) Jc.Amber else Jc.Focus, RoundedCornerShape(3.dp)))
            }
        }
    }
}
