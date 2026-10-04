package com.mackandall.jdvr.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.ui.Jc
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class TransportIcon { Previous, Rewind, Stop, Play, Pause, Forward, Next, Record }

/** One transport action: what it draws, whether it does anything here, and what it does. */
class TransportAction(
    val icon: TransportIcon,
    val enabled: Boolean = true,
    // Lit up: e.g. Record while the programme is being recorded.
    val active: Boolean = false,
    val onClick: () -> Unit,
)

private val NowFormat = DateTimeFormatter.ofPattern("EEE, MMM d  h:mm a")

/**
 * The row of transport buttons under the progress bar (SageTV's layout): previous, rewind, stop,
 * play/pause, fast forward, next; then any extra buttons, and the date and time at the right.
 * [playFocus] goes on the play/pause button, where focus lands when the controls open.
 */
@Composable
fun TransportBar(
    previous: TransportAction,
    rewind: TransportAction,
    stop: TransportAction,
    playPause: TransportAction,
    forward: TransportAction,
    next: TransportAction,
    playFocus: FocusRequester,
    record: TransportAction? = null,
    extras: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransportButton(previous)
        TransportButton(rewind)
        TransportButton(stop)
        TransportButton(playPause, Modifier.focusRequester(playFocus))
        TransportButton(forward)
        TransportButton(next)
        record?.let {
            Spacer(Modifier.width(6.dp))
            TransportButton(it)
        }
        Spacer(Modifier.width(14.dp))
        extras()
        Spacer(Modifier.weight(1f))
        Text(LocalDateTime.now().format(NowFormat), color = Jc.Text, fontSize = 16.sp)
    }
}

@Composable
private fun TransportButton(
    action: TransportAction,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { if (action.enabled) action.onClick() },
        modifier = modifier.size(width = 58.dp, height = 44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (action.active) Jc.Recording.copy(alpha = 0.28f) else Jc.Panel2,
                focusedContainerColor = if (action.active) Jc.Recording.copy(alpha = 0.45f) else Jc.FocusFill,
                contentColor = if (action.enabled) Jc.Text else Jc.Muted.copy(alpha = 0.45f),
                focusedContentColor = if (action.enabled) Jc.Text else Jc.Muted,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        val color = LocalContentColor.current
        Box(Modifier.size(width = 58.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            val tint = if (action.icon == TransportIcon.Record && action.enabled) Jc.Recording else color
            Canvas(Modifier.size(20.dp)) { drawIcon(action.icon, tint) }
        }
    }
}

/** Plain shapes, so the icons look the same on every TV (no emoji font). */
private fun DrawScope.drawIcon(
    icon: TransportIcon,
    color: Color,
) {
    val w = size.width
    val h = size.height
    fun triangle(
        left: Float,
        right: Float,
        pointsRight: Boolean,
    ) {
        val p = Path()
        if (pointsRight) {
            p.moveTo(left, 0f)
            p.lineTo(right, h / 2)
            p.lineTo(left, h)
        } else {
            p.moveTo(right, 0f)
            p.lineTo(left, h / 2)
            p.lineTo(right, h)
        }
        p.close()
        drawPath(p, color)
    }
    fun bar(
        left: Float,
        width: Float,
    ) = drawRect(color, Offset(left, 0f), Size(width, h))
    when (icon) {
        TransportIcon.Play -> triangle(w * 0.15f, w * 0.95f, true)
        TransportIcon.Pause -> {
            bar(w * 0.15f, w * 0.25f)
            bar(w * 0.6f, w * 0.25f)
        }
        TransportIcon.Stop -> drawRect(color, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f))
        TransportIcon.Rewind -> {
            triangle(0f, w / 2, false)
            triangle(w / 2, w, false)
        }
        TransportIcon.Forward -> {
            triangle(0f, w / 2, true)
            triangle(w / 2, w, true)
        }
        TransportIcon.Previous -> {
            bar(w * 0.1f, w * 0.15f)
            triangle(w * 0.25f, w * 0.9f, false)
        }
        TransportIcon.Next -> {
            triangle(w * 0.1f, w * 0.75f, true)
            bar(w * 0.75f, w * 0.15f)
        }
        TransportIcon.Record -> drawCircle(color, radius = w * 0.42f)
    }
}
