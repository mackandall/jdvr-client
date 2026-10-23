package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/** A soft backdrop: a base colour with blurred patches of colour over it (light themes). */
data class Wash(
    val key: String,
    val name: String,
    val base: Color,
    // Each patch: centre (fractions of the screen), radius (fraction of the width), colour.
    val spots: List<Triple<Offset, Float, Color>>,
)

val Washes =
    listOf(
        Wash("blue", "Dusty blue", Color(0xFFAFC4D6), listOf(Triple(Offset(0.15f, 0.1f), 0.7f, Color(0xFFD6E1EA)), Triple(Offset(0.9f, 0.8f), 0.62f, Color(0xFF93AFC6)), Triple(Offset(0.7f, 0.2f), 0.45f, Color(0xFFC9D2DC)))),
        Wash("sage", "Sage", Color(0xFFA9C0BC), listOf(Triple(Offset(0.2f, 0.05f), 0.7f, Color(0xFFD3E0DC)), Triple(Offset(0.9f, 0.85f), 0.62f, Color(0xFF8CAAA7)))),
        Wash("lavender", "Lavender", Color(0xFFC2C8DA), listOf(Triple(Offset(0.15f, 0.1f), 0.7f, Color(0xFFE1E4EF)), Triple(Offset(0.85f, 0.85f), 0.62f, Color(0xFFA9B2CC)))),
    )

fun washFor(key: String?): Wash = Washes.firstOrNull { it.key == key } ?: Washes.first()

/**
 * A colour theme: the backgrounds, panels, text and focus colour, and the status colours
 * (recording red, commercial amber, watched green, star gold), which a light theme darkens.
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
    val recording: Color = Color(0xFFE5484D),
    val amber: Color = Color(0xFFF5A524),
    // Text on amber (the CS and Partial tags).
    val onAmber: Color = Color(0xFF1A1206),
    val green: Color = Color(0xFF2EA36B),
    val gold: Color = Color(0xFFF2C94C),
    // Unfocused buttons; a light theme makes them slate tiles with white text.
    val button: Color? = null,
    // Panels that open over other things (side panels, dialogs): solid even when panels are frosted.
    val sheet: Color? = null,
    val onButton: Color? = null,
    // Light: a soft wash behind the screens (Settings › Backdrop), white focus, dark text.
    val light: Boolean = false,
    // A light theme without the wash: its ink, solid, behind the screens (and no Backdrop setting).
    val wash: Boolean = true,
    // SageTV's themes change more than colours: Home becomes their main menu ([UiStyle]).
    val style: UiStyle = UiStyle.Standard,
)

/** How much a theme changes besides its colours. */
enum class UiStyle {
    Standard,

    // SageTV's default theme: Home is a vertical main menu with submenus, glossy blue selection.
    Sage,

    // SageTV's Gemstone theme: fanart behind black glass, the same side menu in one gem colour.
    Gem,
}

/** Gemstone's accent (the swatches under its card in the theme gallery). */
data class Gem(
    val key: String,
    val name: String,
    val color: Color,
)

val Gems =
    listOf(
        Gem("emerald", "Emerald", Color(0xFF3DDC97)),
        Gem("sapphire", "Sapphire", Color(0xFF5AA9FF)),
        Gem("ruby", "Ruby", Color(0xFFFF5C7A)),
        Gem("amethyst", "Amethyst", Color(0xFFB993FF)),
        Gem("topaz", "Topaz", Color(0xFFFFB648)),
    )

fun gemFor(key: String?): Gem = Gems.firstOrNull { it.key == key } ?: Gems.first()

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
        // After Windows Media Center: royal blue, white type, a bright blue bar on the selection.
        Palette(
            "mediacenter", "Media Center",
            ink = Color(0xFF061A3D), rail = Color(0xFF08214A), panel = Color(0xFF0C2C5E), panel2 = Color(0xFF123A78), line = Color(0xFF1F4E94),
            text = Color(0xFFFFFFFF), muted = Color(0xFFA9C3E8), focus = Color(0xFF2E8BEA), focusFill = Color(0xFF14427F), onFocus = Color(0xFFFFFFFF),
        ),
        // After MythTV's classic menus: indigo panes, silver type, an amber selection.
        Palette(
            "mythblue", "MythTV Blue",
            ink = Color(0xFF0A0F2C), rail = Color(0xFF0F1640), panel = Color(0xFF16205A), panel2 = Color(0xFF1E2B70), line = Color(0xFF2C3B8C),
            text = Color(0xFFF2F2F2), muted = Color(0xFFAEB8DE), focus = Color(0xFFF2B33D), focusFill = Color(0xFF2A2A55), onFocus = Color(0xFF1A1206),
        ),
        // After Kodi's default skin: dark slate, white type, a light-blue focus.
        Palette(
            "estuary", "Estuary",
            ink = Color(0xFF13171C), rail = Color(0xFF181D23), panel = Color(0xFF1E242B), panel2 = Color(0xFF2A3139), line = Color(0xFF353D46),
            text = Color(0xFFFFFFFF), muted = Color(0xFFA0A8B2), focus = Color(0xFF12B2E7), focusFill = Color(0xFF0F3A4A), onFocus = Color(0xFF021A24),
        ),
        // After TiVo: purple-blue screens with a lime highlight.
        Palette(
            "peanut", "Peanut",
            ink = Color(0xFF120A2E), rail = Color(0xFF170D3A), panel = Color(0xFF1F1250), panel2 = Color(0xFF2A1A66), line = Color(0xFF3B2785),
            text = Color(0xFFFFFFFF), muted = Color(0xFFBBB0E0), focus = Color(0xFF9BE15D), focusFill = Color(0xFF2E2A5E), onFocus = Color(0xFF132006),
        ),
        // Warm charcoal with a coral focus.
        Palette(
            "ember", "Ember",
            ink = Color(0xFF14110F), rail = Color(0xFF191512), panel = Color(0xFF201B17), panel2 = Color(0xFF2B241F), line = Color(0xFF3A312A),
            text = Color(0xFFF3ECE4), muted = Color(0xFFB3A699), focus = Color(0xFFFF8A65), focusFill = Color(0xFF3B2620), onFocus = Color(0xFF2A0F06),
        ),
        // Navy panels and a cyan focus.
        Palette(
            "deepsea", "Deep sea",
            ink = Color(0xFF06141F), rail = Color(0xFF091A28), panel = Color(0xFF0D2233), panel2 = Color(0xFF132D42), line = Color(0xFF1C3A52),
            text = Color(0xFFE2EEF6), muted = Color(0xFF8EA8BC), focus = Color(0xFF4DD0E1), focusFill = Color(0xFF0F3A47), onFocus = Color(0xFF02191E),
        ),
        // After the old cable guide channel: a royal-blue grid with a yellow highlight.
        Palette(
            "prevue", "Prevue",
            ink = Color(0xFF0A1650), rail = Color(0xFF0C1A5E), panel = Color(0xFF13278A), panel2 = Color(0xFF1A34A8), line = Color(0xFF2A46C0),
            text = Color(0xFFFFFFFF), muted = Color(0xFFC3CCF4), focus = Color(0xFFFFD400), focusFill = Color(0xFF22379F), onFocus = Color(0xFF0A1650),
            recording = Color(0xFFFF5A5F), amber = Color(0xFFFF9F1C), green = Color(0xFF3DDC84),
        ),
        // After SageTV's default theme: navy lit from the top left, a glossy cyan-blue selection.
        Palette(
            "sagetv", "SageTV",
            ink = Color(0xFF0A1D4A), rail = Color(0xFF0C2257), panel = Color(0xFF0E2A63).copy(alpha = 0.78f), panel2 = Color(0xFF1B3B7C), line = Color(0xFF2D5AA8),
            text = Color(0xFFFFFFFF), muted = Color(0xFF9DB6DA), focus = Color(0xFF5FB0FF), focusFill = Color(0xFF2470D8), onFocus = Color(0xFFFFFFFF),
            recording = Color(0xFFFF5A5A), green = Color(0xFF3FAE6E), sheet = Color(0xFF0E2A63), style = UiStyle.Sage,
        ),
        // After SageTV's Gemstone theme: black glass over fanart, one gem colour (picked in the theme gallery).
        Palette(
            "gemstone", "SageTV Gemstone",
            ink = Color(0xFF0B0C0F), rail = Color(0xFF101115), panel = Color(0xFF0E0F13).copy(alpha = 0.82f), panel2 = Color(0xFF1C1F26), line = Color(0xFF2C3038),
            text = Color(0xFFF2F3F5), muted = Color(0xFF9AA0AA), focus = Gems.first().color, focusFill = Color(0xFF1C2A25), onFocus = Color(0xFF07140E),
            sheet = Color(0xFF14161B), style = UiStyle.Gem,
        ),
        // Light, after Emby's Apple TV theme: frosted panels on a soft wash, slate tiles, white focus.
        Palette(
            "ode", "Ode to Emby's Apple",
            ink = Color(0xFFBFD0DE), rail = Color(0xFFC9D7E3), panel = Color.White.copy(alpha = 0.42f), panel2 = Color.White.copy(alpha = 0.6f), line = Color(0xFF93A6B8),
            text = Color(0xFF1F2A33), muted = Color(0xFF4C5B69), focus = Color(0xFF2F4F6B), focusFill = Color.White, onFocus = Color.White,
            recording = Color(0xFFC62828), amber = Color(0xFF9A4508), onAmber = Color.White, green = Color(0xFF2E7D32), gold = Color(0xFF8A6D00),
            button = Color(0xFF5F7890), onButton = Color.White, sheet = Color(0xFFDCE6EE), light = true,
        ),
        // Light and solid: warm off-white like newsprint, an ink-green focus.
        Palette(
            "paper", "Paper",
            ink = Color(0xFFF3EFE7), rail = Color(0xFFEAE4D8), panel = Color(0xFFFFFDF8), panel2 = Color(0xFFE3DBCB), line = Color(0xFFD3CABA),
            text = Color(0xFF23201B), muted = Color(0xFF5F584D), focus = Color(0xFF1F6B57), focusFill = Color(0xFFDCEDE5), onFocus = Color.White,
            recording = Color(0xFFC62828), amber = Color(0xFF9A4508), onAmber = Color.White, green = Color(0xFF2E7D32), gold = Color(0xFF8A6D00),
            light = true, wash = false,
        ),
        // Light and solid: crisp cool white, a strong blue focus.
        Palette(
            "daylight", "Daylight",
            ink = Color(0xFFEEF2F7), rail = Color(0xFFE3E9F1), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFD9E2ED), line = Color(0xFFC6D1DE),
            text = Color(0xFF111827), muted = Color(0xFF4B5563), focus = Color(0xFF1D4ED8), focusFill = Color(0xFFDBE6FB), onFocus = Color.White,
            recording = Color(0xFFC62828), amber = Color(0xFF9A4508), onAmber = Color.White, green = Color(0xFF2E7D32), gold = Color(0xFF8A6D00),
            light = true, wash = false,
        ),
    )

fun paletteFor(key: String?): Palette = Palettes.firstOrNull { it.key == key } ?: Palettes.first()

/** The theme as shown: Gemstone takes its focus colours from the chosen gem. */
fun paletteFor(
    key: String?,
    gem: String?,
): Palette {
    val p = paletteFor(key)
    if (p.style != UiStyle.Gem) return p
    val c = gemFor(gem).color
    return p.copy(focus = c, focusFill = lerp(Color(0xFF14161B), c, 0.22f))
}

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
    val Recording get() = palette.recording
    val Amber get() = palette.amber
    val OnAmber get() = palette.onAmber
    val Green get() = palette.green
    val Gold get() = palette.gold
    val Button get() = palette.button ?: palette.panel2
    val Sheet get() = palette.sheet ?: palette.panel
    val OnButton get() = palette.onButton ?: palette.text
    val Light get() = palette.light
    val Style get() = palette.style

    // The light themes' backdrop (Settings › Backdrop).
    var wash by mutableStateOf(Washes.first())
}

/**
 * A panel made see-through (over art, a picture or video): [alpha] of the panel's own opacity, so a
 * light theme's frosted panels get lighter still rather than turning solid white.
 */
fun Color.seeThrough(alpha: Float): Color = copy(alpha = this.alpha * alpha)

/** A screen's background: the theme's ink, or for a light theme with a wash its soft wash. */
fun Modifier.screenBackground(): Modifier =
    drawBehind {
        if (Jc.Style == UiStyle.Sage) {
            // SageTV's backdrop: navy, lit from the top left.
            drawRect(Color(0xFF06122F))
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF2A5BB0), Color(0xFF133779), Color(0xFF0A1D4A), Color(0xFF06122F)),
                    center = Offset(size.width * 0.18f, size.height * 0.1f),
                    radius = size.width * 0.75f,
                ),
            )
            return@drawBehind
        }
        if (!Jc.Light || !Jc.palette.wash) {
            drawRect(Jc.Ink)
            return@drawBehind
        }
        val w = Jc.wash
        drawRect(w.base)
        w.spots.forEach { (c, r, color) ->
            val centre = Offset(c.x * size.width, c.y * size.height)
            drawRect(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = centre, radius = r * size.width))
        }
    }

@Composable
fun JdvrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            if (Jc.Light) {
                lightColorScheme(primary = Jc.Focus, background = Jc.Ink, surface = Jc.Panel, onSurface = Jc.Text, onBackground = Jc.Text)
            } else {
                darkColorScheme(primary = Jc.Focus, background = Jc.Ink, surface = Jc.Panel, onSurface = Jc.Text, onBackground = Jc.Text)
            },
        content = content,
    )
}
