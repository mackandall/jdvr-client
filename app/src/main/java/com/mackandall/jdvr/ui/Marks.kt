package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Dot(color: androidx.compose.ui.graphics.Color) = Box(Modifier.size(12.dp).background(color, CircleShape))

/**
 * Record series' mark: two of Record's dots, the second over half of the first, a thin gap
 * cut round it so both show on any button colour.
 */
@Composable
fun SeriesDots(
    color: androidx.compose.ui.graphics.Color = Jc.Recording,
    height: androidx.compose.ui.unit.Dp = 12.dp,
    // Cancel series recording: the front dot slashed as Cancel recording's, the one behind paler.
    crossed: Boolean = false,
) =
    androidx.compose.foundation.Canvas(
        Modifier
            .size(width = height * 1.5f, height = height)
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen),
    ) {
        val r = size.height / 2
        drawCircle(
            if (crossed) color.copy(alpha = 0.45f) else color,
            r,
            androidx.compose.ui.geometry
                .Offset(r, r),
        )
        val second =
            androidx.compose.ui.geometry
                .Offset(size.width - r, r)
        drawCircle(androidx.compose.ui.graphics.Color.Black, r + size.height / 8, second, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        drawCircle(color, r, second)
        if (crossed) slash(left = size.width - size.height)
    }

/** Cancel recording's mark: Record's dot with a white slash through it. */
@Composable
fun CancelDot(color: androidx.compose.ui.graphics.Color = Jc.Recording) =
    androidx.compose.foundation.Canvas(
        Modifier.size(14.dp).graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen),
    ) {
        drawCircle(color)
        slash(left = 0f)
    }

// Across the dot whose square starts at [left]: bottom left to top right (the same angle on both
// marks), white, cut clear of the red around it so it reads on any button.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.slash(left: Float) {
    val h = size.height
    val a =
        androidx.compose.ui.geometry
            .Offset(left + h * 0.08f, h * 0.98f)
    val b =
        androidx.compose.ui.geometry
            .Offset(left + h * 0.92f, h * 0.02f)
    drawLine(androidx.compose.ui.graphics.Color.Black, a, b, strokeWidth = h * 0.34f, cap = androidx.compose.ui.graphics.StrokeCap.Round, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
    drawLine(androidx.compose.ui.graphics.Color.White, a, b, strokeWidth = h * 0.15f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
}

/** A delete button's mark: the menu's trash can, so it doesn't look like Record's dot. */
@Composable
fun TrashMark(color: androidx.compose.ui.graphics.Color = Jc.Recording) = MenuGlyph(MenuIcon.Trash, IconSets.first(), 18.dp, color, color)
