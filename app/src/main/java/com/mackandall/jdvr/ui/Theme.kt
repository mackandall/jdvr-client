package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * A colour theme: the backgrounds, panels, text and focus colour. The status colours
 * (recording red, commercial amber, watched green, star gold) are the same in every theme.
 */
data class Palette(
    val key: String,
    val name: String,
    val ink: Color,
    val rail: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val focus: Color,
    val focusFill: Color,
    // Text on the focus colour (a primary button).
    val onFocus: Color,
)

val Palettes =
    listOf(
        // The mockup's "broadcast console" palette.
        Palette(
            "midnight", "Midnight",
            ink = Color(0xFF0D1117), rail = Color(0xFF11161D), panel = Color(0xFF161B22), panel2 = Color(0xFF1F2630), line = Color(0xFF222A35),
            text = Color(0xFFE6EAF0), muted = Color(0xFF9AA4B2), focus = Color(0xFF3BA3FF), focusFill = Color(0xFF1E2A3A), onFocus = Color(0xFF06121F),
        ),
        Palette(
            "graphite", "Graphite",
            ink = Color(0xFF111111), rail = Color(0xFF151515), panel = Color(0xFF1B1B1B), panel2 = Color(0xFF262626), line = Color(0xFF2E2E2E),
            text = Color(0xFFEDEDED), muted = Color(0xFFA3A3A3), focus = Color(0xFFE6E6E6), focusFill = Color(0xFF333333), onFocus = Color(0xFF111111),
        ),
        Palette(
            "forest", "Forest",
            ink = Color(0xFF0B1411), rail = Color(0xFF0E1915), panel = Color(0xFF12201B), panel2 = Color(0xFF1A2B25), line = Color(0xFF21352D),
            text = Color(0xFFE4EEE9), muted = Color(0xFF94A89F), focus = Color(0xFF2BC4B4), focusFill = Color(0xFF15372F), onFocus = Color(0xFF032420),
        ),
        Palette(
            "plum", "Plum",
            ink = Color(0xFF130F1A), rail = Color(0xFF17121F), panel = Color(0xFF1C1626), panel2 = Color(0xFF261E33), line = Color(0xFF30263F),
            text = Color(0xFFEDE8F2), muted = Color(0xFFA89DB6), focus = Color(0xFFF06A9E), focusFill = Color(0xFF3A1F34), onFocus = Color(0xFF2A0816),
        ),
    )

fun paletteFor(key: String?): Palette = Palettes.firstOrNull { it.key == key } ?: Palettes.first()

/** The app's colours, from the current theme (Settings › Appearance); screens follow a change at once. */
object Jc {
    var palette by mutableStateOf(Palettes.first())

    val Ink get() = palette.ink
    val Rail get() = palette.rail
    val Panel get() = palette.panel
    val Panel2 get() = palette.panel2
    val Line get() = palette.line
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Focus get() = palette.focus
    val FocusFill get() = palette.focusFill
    val OnFocus get() = palette.onFocus
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
