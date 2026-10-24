package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.LiveTvRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Text on a coloured tag: dark on amber; on a light theme, white on the darker status colours. */
fun badgeText(
    background: Color,
    onAmber: Boolean = false,
): Color =
    when {
        onAmber -> Jc.OnAmber
        Jc.Light && background.luminance() < 0.4f -> Color.White
        else -> Jc.Text
    }

fun Modifier.onFocusChangedCompat(onChange: (Boolean) -> Unit): Modifier = onFocusChanged { onChange(it.isFocused) }

/** Shown where channels or the guide would be, on a server with no Live TV. */
const val NO_LIVE_TV =
    "This server has no Live TV channels. Once a tuner is set up in Jellyfin's dashboard (Live TV), its channels and guide show here."

/**
 * Tunes a Live TV channel in the player ("Watch live" on a recording in progress). Provided around
 * the screens; null in the players (they have their own Back to live).
 */
val LocalWatchLive = androidx.compose.runtime.compositionLocalOf<((java.util.UUID) -> Unit)?> { null }

/** Buttons stretch to the full width of what holds them (a menu's column), where this is true. */
val LocalWideButtons = androidx.compose.runtime.compositionLocalOf { false }

/**
 * A menu's buttons in two columns side by side, each button as wide as its column, so a long menu
 * stays on the screen. ◄ ► cross between the columns, ▲ ▼ move within one.
 */
@Composable
fun MenuColumns(
    left: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    // Each column only as wide as its longest button (not halves of the space there is).
    fit: Boolean = false,
) {
    val column = if (fit) Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max) else null
    androidx.compose.foundation.layout.Row(if (fit) Modifier else Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWideButtons provides true) {
            androidx.compose.foundation.layout.Column(column ?: Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), content = left)
            androidx.compose.foundation.layout.Column(column ?: Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), content = right)
        }
    }
}

/** A remote-friendly button: blue ring when focused. */
@Composable
fun Button(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    // Smaller, for a title row (the guide's channel group).
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    // SageTV: the focused button is its glossy blue bar.
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val sage = Jc.Style == UiStyle.Sage
    Surface(
        onClick = onClick,
        modifier = (if (LocalWideButtons.current) modifier.then(Modifier.fillMaxWidth()) else modifier).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (primary) Jc.Focus else Jc.Button,
                // Light: whatever the button, focus turns it white (as Emby's Apple theme does).
                focusedContainerColor = if (primary && !Jc.Light) Jc.Focus else Jc.FocusFill,
                contentColor = if (primary) Jc.OnFocus else Jc.OnButton,
                focusedContentColor = if (primary && !Jc.Light) Jc.OnFocus else Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (Jc.Light) 1.06f else 1.03f),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = (if (sage && focused) Modifier.sageGloss() else Modifier).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 5.dp else 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(label, fontSize = if (compact) 14.sp else 16.sp)
        }
    }
}

/** SageTV's selection: a glossy blue bar, light on top, a sharp band halfway down. */
fun Modifier.sageGloss(): Modifier =
    drawBehind {
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color(0xFF5FB0FF),
                0.48f to Color(0xFF2C7BE0),
                0.52f to Color(0xFF1A5BC4),
                1f to Color(0xFF2470D8),
            ),
        )
        drawRect(Color.White.copy(alpha = 0.55f), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
    }

private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val DayFormat = DateTimeFormatter.ofPattern("EEE MMM d")

fun LocalDateTime.time(): String = format(TimeFormat)

fun LocalDateTime.day(): String = format(DayFormat)

/**
 * Episode History's guide marks, read off the front of an episode title ("✓💾 Ranch Dressing"):
 * shown as coloured chips, with the clean title. Its symbols are configurable, so they come from
 * the plugin.
 */
data class Marks(
    val watched: Boolean = false,
    val inLibrary: Boolean = false,
    val recordedBefore: Boolean = false,
    val skipped: Boolean = false,
    val otherAiring: Boolean = false,
    val title: String? = null,
) {
    val any: Boolean get() = watched || inLibrary || recordedBefore || skipped || otherAiring

    companion object {
        private val Labels = setOf("Watched", "In library", "Recorded before", "Not recording", "Recording another airing")

        fun parse(
            raw: String?,
            symbols: LiveTvRepository.MarkSymbols?,
        ): Marks {
            if (raw.isNullOrEmpty() || symbols == null) return Marks(title = raw)
            val known =
                listOf(symbols.watched, symbols.inLibrary, symbols.recordedBefore, symbols.skipped, symbols.otherAiring)
                    .filter { it.isNotEmpty() }
                    .sortedByDescending { it.length }
            var rest: String = raw
            val seen = mutableSetOf<String>()
            while (true) {
                val s = known.firstOrNull { rest.startsWith(it) } ?: break
                seen += s
                rest = rest.substring(s.length)
            }
            if (seen.isEmpty()) return Marks(title = raw)
            rest = rest.trimStart()
            return Marks(
                watched = symbols.watched in seen,
                inLibrary = symbols.inLibrary in seen,
                recordedBefore = symbols.recordedBefore in seen,
                skipped = symbols.skipped.isNotEmpty() && symbols.skipped in seen,
                otherAiring = symbols.otherAiring.isNotEmpty() && symbols.otherAiring in seen,
                title = rest.takeUnless { it.isEmpty() || it in Labels },
            )
        }
    }
}

/** Scrolls a focused item just into view (no TV-style pivot), or not at all when it's already in view. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MinimalScroll =
    object : androidx.compose.foundation.gestures.BringIntoViewSpec {
        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float,
        ): Float {
            val trailing = offset + size
            return when {
                offset >= 0 && trailing <= containerSize -> 0f
                offset < 0 && trailing > containerSize -> 0f
                kotlin.math.abs(offset) < kotlin.math.abs(trailing - containerSize) -> offset
                else -> trailing - containerSize
            }
        }
    }
