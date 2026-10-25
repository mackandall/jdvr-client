package com.mackandall.jdvr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.Library

/**
 * The side menu's icons (Settings › Menu): what each entry shows, in sets of one look. A set is a
 * drawing per [MenuIcon] on a 24×24 grid: lines ([Glyph.strokes]) and filled shapes
 * ([Glyph.fills], even-odd, so a shape can have holes). A new set is one more [IconSet] in
 * [IconSets]; a new entry, one more [MenuIcon] with a drawing in each set (a set without one
 * falls back to the Line set's).
 */
enum class MenuIcon { Search, Home, Guide, LiveTv, Recorded, Libraries, Scheduled, Series, Trash, Settings, Profile, Server, Exit, Movies, Tv, Archive, MoviesArchive, TvArchive, Music, Folder }

class Glyph(
    val strokes: List<String> = emptyList(),
    val fills: List<String> = emptyList(),
    // Line width on the 24 grid; the set's own when null.
    val stroke: Float? = null,
)

class IconSet(
    val key: String,
    val name: String,
    val glyphs: Map<MenuIcon, Glyph>,
    val stroke: Float,
    // Drawn on a soft tile of the accent colour, the glyph a little smaller inside it.
    val tile: Boolean = false,
)

/** The icon for a screen's menu entry. */
fun Screen.menuIcon(): MenuIcon =
    when (this) {
        Screen.Search -> MenuIcon.Search
        Screen.Home -> MenuIcon.Home
        Screen.Guide -> MenuIcon.Guide
        Screen.LiveTv -> MenuIcon.LiveTv
        Screen.RecordedShows -> MenuIcon.Recorded
        Screen.Library -> MenuIcon.Libraries
        Screen.Scheduled -> MenuIcon.Scheduled
        Screen.Series -> MenuIcon.Series
        Screen.Trash -> MenuIcon.Trash
        Screen.Settings -> MenuIcon.Settings
    }

/** A library's icon: by what it holds; an archive (by its name) as stored film, an old TV set, or a box. */
fun Library.menuIcon(): MenuIcon =
    when {
        name.contains("archive", ignoreCase = true) ->
            when (kind) {
                "movies" -> MenuIcon.MoviesArchive
                "tvshows" -> MenuIcon.TvArchive
                else -> MenuIcon.Archive
            }
        kind == "movies" -> MenuIcon.Movies
        kind == "tvshows" || kind == "livetv" -> MenuIcon.Tv
        kind == "music" -> MenuIcon.Music
        else -> MenuIcon.Folder
    }

private val LINE =
    mapOf(
        MenuIcon.Search to Glyph(listOf("M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z", "M16 16l4.5 4.5")),
        MenuIcon.Home to Glyph(listOf("M3.5 11 12 4l8.5 7", "M5.5 9.5V20h13V9.5", "M10 20v-5h4v5")),
        MenuIcon.Guide to Glyph(listOf("M5 4.5h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z", "M3 9.5h18M3 14.5h18M9 4.5v5M15 9.5v5M7 14.5v5")),
        MenuIcon.LiveTv to Glyph(listOf("M5 7h14a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M8.5 3.5 12 7l3.5-3.5", "M14.2 13.25a2.2 2.2 0 1 1-4.4 0a2.2 2.2 0 1 1 4.4 0z")),
        MenuIcon.Recorded to Glyph(listOf("M8.5 7.5h10.5a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8.5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M3 15.5V6.5a2 2 0 0 1 2-2h11.5", "M12 11v5l4-2.5z")),
        MenuIcon.Libraries to Glyph(listOf("M4.5 4.5h2.5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H4.5a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z", "M10.5 4.5H13a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-2.5a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z", "m15.6 6.2 3.6-1 3.1 13.9-3.6 1z")),
        MenuIcon.Scheduled to Glyph(listOf("M5 5h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 9.5h18M8 3v4M16 3v4", "M12 12.5v3l2 1.2")),
        MenuIcon.Series to Glyph(listOf("M4 11V9a3 3 0 0 1 3-3h12", "m16 3 3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H5", "m8 21-3-3 3-3")),
        MenuIcon.Trash to Glyph(listOf("M4 7h16", "M9.5 7V4.5h5V7", "M6.5 7l1 13h9l1-13", "M10.5 11v5.5M13.5 11v5.5")),
        MenuIcon.Settings to Glyph(listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")),
        MenuIcon.Profile to Glyph(listOf("M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M4 21a8 8 0 0 1 16 0")),
        MenuIcon.Server to Glyph(listOf("M5 4h14a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 19 10H5a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 5 4z", "M5 14h14a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 19 20H5a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 5 14z", "M7.5 7h.01M7.5 17h.01M11 7h5.5M11 17h5.5")),
        MenuIcon.Exit to Glyph(listOf("M12 3v9", "M17.4 6.6a8 8 0 1 1-10.8 0")),
        MenuIcon.Movies to Glyph(listOf("M4 10h16v9.5H4z", "M4 10 3.4 6.5 18.8 3.7l.6 3.5z", "m8.2 5.8 2.1 3.2M12.8 5l2.1 3.2")),
        MenuIcon.Tv to Glyph(listOf("M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8 20h8M12 16v4")),
        MenuIcon.Archive to Glyph(listOf("M4.2 4h15.6a1.2 1.2 0 0 1 1.2 1.2v2.6a1.2 1.2 0 0 1-1.2 1.2H4.2A1.2 1.2 0 0 1 3 7.8V5.2A1.2 1.2 0 0 1 4.2 4z", "M5 9v10.5h14V9", "M10 13h4")),
        MenuIcon.MoviesArchive to Glyph(listOf("M19 11.5a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "M12.6 11.5a1.6 1.6 0 1 1-3.2 0a1.6 1.6 0 1 1 3.2 0z", "M12.7 7.3a1.7 1.7 0 1 1-3.4 0a1.7 1.7 0 1 1 3.4 0z", "M12.7 15.7a1.7 1.7 0 1 1-3.4 0a1.7 1.7 0 1 1 3.4 0z", "M8.5 11.5a1.7 1.7 0 1 1-3.4 0a1.7 1.7 0 1 1 3.4 0z", "M16.9 11.5a1.7 1.7 0 1 1-3.4 0a1.7 1.7 0 1 1 3.4 0z", "M11 19.5h10")),
        MenuIcon.TvArchive to Glyph(listOf("M5 7.5h14a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H5A2.5 2.5 0 0 1 2.5 18v-8A2.5 2.5 0 0 1 5 7.5z", "M6.3 10.5h7.9a1.2 1.2 0 0 1 1.2 1.2v4.6a1.2 1.2 0 0 1-1.2 1.2H6.3a1.2 1.2 0 0 1-1.2-1.2v-4.6a1.2 1.2 0 0 1 1.2-1.2z", "M19.4 11.6a1 1 0 1 1-2 0a1 1 0 1 1 2 0z", "M19.4 15.4a1 1 0 1 1-2 0a1 1 0 1 1 2 0z", "M10 7.5 15.2 3.3")),
        MenuIcon.Music to Glyph(listOf("M9 18V5l12-2v13", "M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z", "M21 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")),
        MenuIcon.Folder to Glyph(listOf("M5 5h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")),
    )

private val SOLID =
    mapOf(
        MenuIcon.Search to Glyph(fills = listOf("M10.5 3.5a7 7 0 1 0 0 14a7 7 0 1 0 0-14z M10.5 6.9a3.6 3.6 0 1 1 0 7.2a3.6 3.6 0 1 1 0-7.2z"), strokes = listOf("M15.6 15.6l4.9 4.9"), stroke = 3f),
        MenuIcon.Home to Glyph(fills = listOf("M12 3.2 2.8 10.9l1.3 1.5.9-.8v8.9h5.2v-5.3h3.6v5.3H19v-8.9l.9.8 1.3-1.5z")),
        MenuIcon.Guide to Glyph(fills = listOf("M3 5h6v4H3z M10.5 5H21v4H10.5z M3 10.5h9.5v4H3z M14 10.5h7v4h-7z M3 16h4.5v4H3z M9 16h12v4H9z")),
        MenuIcon.LiveTv to Glyph(fills = listOf("M4.5 7h15A2.5 2.5 0 0 1 22 9.5v8a2.5 2.5 0 0 1-2.5 2.5h-15A2.5 2.5 0 0 1 2 17.5v-8A2.5 2.5 0 0 1 4.5 7z M5 10v7h14v-7z M12 11.3a2.2 2.2 0 1 0 0 4.4a2.2 2.2 0 1 0 0-4.4z"), strokes = listOf("M8.5 3.5 12 7l3.5-3.5"), stroke = 2.2f),
        MenuIcon.Recorded to Glyph(fills = listOf("M8 8h11.5a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z M12.2 11.4v5.2l4.3-2.6z"), strokes = listOf("M3 16V6a2 2 0 0 1 2-2h12"), stroke = 2.2f),
        MenuIcon.Libraries to Glyph(fills = listOf("M3.5 4.5h4.5v15H3.5z M9.5 4.5H14v15H9.5z M15.4 6.3l3.7-1 3.2 14-3.7 1z")),
        MenuIcon.Scheduled to Glyph(fills = listOf("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M5 10v9h14v-9z"), strokes = listOf("M8 3v4M16 3v4", "M12 12.3v3l2 1.2"), stroke = 2.2f),
        MenuIcon.Series to Glyph(strokes = listOf("M4 11V9a3 3 0 0 1 3-3h12", "m16 3 3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H5", "m8 21-3-3 3-3"), stroke = 2.8f),
        MenuIcon.Trash to Glyph(fills = listOf("M6 8h12l-1.1 12.5H7.1z"), strokes = listOf("M4 6.5h16", "M9.5 6.5V4h5v2.5"), stroke = 2.4f),
        MenuIcon.Settings to Glyph(fills = listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")),
        MenuIcon.Profile to Glyph(fills = listOf("M12 3.5a4.5 4.5 0 1 1 0 9a4.5 4.5 0 1 1 0-9z M3.5 21a8.5 8.5 0 0 1 17 0z")),
        MenuIcon.Exit to Glyph(strokes = listOf("M12 3v9", "M17.4 6.6a8 8 0 1 1-10.8 0"), stroke = 2.8f),
        MenuIcon.Movies to Glyph(fills = listOf("M4 10.5h16v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z M3.3 6.6 18.6 3.8l.7 3.8L4 10.4z")),
        MenuIcon.Tv to Glyph(fills = listOf("M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"), strokes = listOf("M8 20h8M12 16v4"), stroke = 2.2f),
        MenuIcon.Archive to Glyph(fills = listOf("M3 4h18v5H3z M4.5 10h15v9.5a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1z M9.5 12.5v1.6h5v-1.6z")),
        MenuIcon.MoviesArchive to Glyph(fills = listOf("M11 3.5a8 8 0 1 0 0 16a8 8 0 1 0 0-16z M11 9.9a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z M11 5.4a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0-3.8z M11 13.8a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0-3.8z M6.8 9.6a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0-3.8z M15.2 9.6a1.9 1.9 0 1 1 0 3.8a1.9 1.9 0 1 1 0-3.8z"), strokes = listOf("M11 19.5h10"), stroke = 2.4f),
        MenuIcon.TvArchive to Glyph(fills = listOf("M5 7.5h14a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5H5A2.5 2.5 0 0 1 2.5 18v-8A2.5 2.5 0 0 1 5 7.5z M6.3 10.5h7.9a1.2 1.2 0 0 1 1.2 1.2v4.6a1.2 1.2 0 0 1-1.2 1.2H6.3a1.2 1.2 0 0 1-1.2-1.2v-4.6a1.2 1.2 0 0 1 1.2-1.2z M18.4 10.5a1.1 1.1 0 1 1 0 2.2a1.1 1.1 0 1 1 0-2.2z M18.4 14.3a1.1 1.1 0 1 1 0 2.2a1.1 1.1 0 1 1 0-2.2z", "M16.2 2.2a1.1 1.1 0 1 1 0 2.2a1.1 1.1 0 1 1 0-2.2z"), strokes = listOf("M10 7.5 15.2 3.3"), stroke = 2.2f),
        MenuIcon.Music to Glyph(fills = listOf("M9 18a3 3 0 1 1-1.9-2.8V5.2L21 3v13a3 3 0 1 1-1.9-2.8V6.6L9 8.3z")),
        MenuIcon.Folder to Glyph(fills = listOf("M5 4h4.5l2 2.5H19a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")),
    )

val IconSets =
    listOf(
        IconSet("line", "Line", LINE, stroke = 1.8f),
        IconSet("solid", "Solid", SOLID, stroke = 2.2f),
        IconSet("tile", "Tile", LINE, stroke = 2f, tile = true),
    )

/** The chosen set, or null when the menu has no icons. */
fun iconSetFor(key: String): IconSet? = if (key == Prefs.OFF) null else IconSets.firstOrNull { it.key == key } ?: IconSets.first()

private val parsedCache = HashMap<String, Path>()

private fun parsed(d: String, evenOdd: Boolean): Path =
    parsedCache.getOrPut((if (evenOdd) "e" else "n") + d) {
        PathParser().parsePathString(d).toPath().apply { if (evenOdd) fillType = PathFillType.EvenOdd }
    }

/**
 * One menu icon, [size] square, in [color]; a tile set draws on a soft square of [accent]. Scale it
 * with a graphicsLayer (the menu's magnify), never by changing [size]: the row's layout stays put.
 */
@Composable
fun MenuGlyph(
    icon: MenuIcon,
    set: IconSet,
    size: Dp,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val glyph = remember(icon, set) { set.glyphs[icon] ?: LINE[icon] ?: Glyph() }
    Canvas(modifier.size(size)) {
        val f = this.size.width / 24f
        if (set.tile) {
            drawRoundRect(accent.copy(alpha = 0.22f), size = this.size, cornerRadius = CornerRadius(this.size.width * 0.28f))
        }
        // Inside a tile the drawing is a little smaller, so it doesn't touch the tile's edge.
        val inner = if (set.tile) 0.72f else 1f
        val offset = (this.size.width * (1 - inner)) / 2
        scale(f * inner, f * inner, pivot = Offset.Zero) {
            val shift = offset / (f * inner)
            translate(shift, shift) {
                glyph.fills.forEach { drawPath(parsed(it, evenOdd = true), color) }
                val w = glyph.stroke ?: set.stroke
                glyph.strokes.forEach {
                    drawPath(parsed(it, evenOdd = false), color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** How much the focused icon grows (Settings › Menu › Focused icon size), before the row's own limit. */
fun menuIconGrowth(size: String): Float =
    when (size) {
        Prefs.SMALL -> 1.2f
        Prefs.LARGE -> 1.7f
        else -> 1.45f
    }

/** An entry's icon scale at [distance] rows from the focused one, for the chosen motion. */
fun menuIconScale(
    motion: String,
    growth: Float,
    distance: Int,
): Float =
    when (motion) {
        Prefs.MAGNIFY -> if (distance == 0) growth else 1f
        Prefs.WAVE ->
            when (distance) {
                0 -> growth
                1 -> 1f + (growth - 1f) * 0.4f
                2 -> 1f + (growth - 1f) * 0.12f
                else -> 1f
            }
        else -> 1f
    }
