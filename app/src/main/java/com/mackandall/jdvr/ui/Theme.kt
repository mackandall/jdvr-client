package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** The mockup's "broadcast console" palette. */
object Jc {
    val Ink = Color(0xFF0D1117)
    val Rail = Color(0xFF11161D)
    val Panel = Color(0xFF161B22)
    val Panel2 = Color(0xFF1F2630)
    val Line = Color(0xFF222A35)
    val Text = Color(0xFFE6EAF0)
    val Muted = Color(0xFF9AA4B2)
    val Focus = Color(0xFF3BA3FF)
    val FocusFill = Color(0xFF1E2A3A)
    val Recording = Color(0xFFE5484D)
    val Amber = Color(0xFFF5A524)
    val Green = Color(0xFF2EA36B)
    val Gold = Color(0xFFF2C94C)
}

@Composable
fun JdvrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Jc.Focus,
                background = Jc.Ink,
                surface = Jc.Panel,
                onSurface = Jc.Text,
                onBackground = Jc.Text,
            ),
        content = content,
    )
}
