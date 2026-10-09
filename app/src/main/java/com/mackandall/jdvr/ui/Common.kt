package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** Buttons stretch to the full width of what holds them (a menu's column), where this is true. */
val LocalWideButtons = androidx.compose.runtime.compositionLocalOf { false }

/** A remote-friendly button: blue ring when focused. */
@Composable
fun Button(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = if (LocalWideButtons.current) modifier.then(Modifier.fillMaxWidth()) else modifier,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(label, fontSize = 16.sp)
        }
    }
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
