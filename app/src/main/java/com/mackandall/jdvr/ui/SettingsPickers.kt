package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import kotlinx.coroutines.delay

/** One choice of a setting: the value kept, and the words shown for it. */
data class Opt<T>(
    val value: T,
    val label: String,
)

/**
 * A setting with several choices. ◄ ► step back and forth through them right on the row; OK flips a
 * two-way setting, and for a longer list opens a picker with all of them ([onOpen] replaces it,
 * as the theme gallery does).
 */
@Composable
internal fun <T> Options(
    label: String,
    help: String,
    options: List<Opt<T>>,
    selected: T,
    modifier: Modifier = Modifier,
    // Shown when the setting matches none of the options (Home layout: "Your own").
    otherLabel: String? = null,
    // Shown before the value (the menu icons' sample).
    preview: (@Composable () -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onChange: (T) -> Unit,
) {
    val index = options.indexOfFirst { it.value == selected }
    var focused by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val many = options.size > 2

    fun step(by: Int) {
        if (options.isEmpty()) return
        val i = if (index < 0) (if (by > 0) 0 else options.lastIndex) else (index + by + options.size) % options.size
        onChange(options[i].value)
    }
    Surface(
        onClick = {
            when {
                !many -> step(1)
                onOpen != null -> onOpen()
                else -> picking = true
            }
        },
        modifier =
            modifier
                .fillMaxWidth(0.8f)
                .onFocusChangedCompat { focused = it }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> {
                            step(-1)
                            true
                        }

                        Key.DirectionRight -> {
                            step(1)
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(help, color = Jc.Muted, fontSize = 14.sp)
            }
            preview?.let {
                Row(Modifier.padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { it() }
            }
            Column(Modifier.width(260.dp)) {
                val shown = options.getOrNull(index)?.label ?: otherLabel ?: ""
                if (focused && many) {
                    // The focused row: its arrows, and where in the list it is. (SageTV's focus is
                    // a bright blue, so its arrows are white.)
                    val arrow = if (Jc.Style == UiStyle.Sage) Jc.Text else Jc.Focus
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("◀", color = arrow, fontSize = 13.sp)
                        Text(shown, color = Jc.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("▶", color = arrow, fontSize = 13.sp)
                    }
                    Text((if (index >= 0) "${index + 1} of ${options.size} · " else "") + "OK all choices", color = Jc.Muted, fontSize = 12.sp)
                } else {
                    Text(shown, color = Jc.Focus, fontSize = 16.sp)
                }
            }
        }
    }
    if (picking) {
        OptionPicker(label, options, index, onPick = {
            picking = false
            onChange(it)
        }, onDismiss = { picking = false })
    }
}

/** Every choice of a setting in a list: ▲▼ move, OK chooses, Back leaves it as it was. */
@Composable
private fun <T> OptionPicker(
    label: String,
    options: List<Opt<T>>,
    current: Int,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        first.focusSoon(withinMs = 1500)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .padding(end = 90.dp)
                    .width(320.dp)
                    .background(Jc.Sheet, RoundedCornerShape(12.dp))
                    .border(1.dp, Jc.Line, RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label.uppercase(), color = Jc.Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                options.forEachIndexed { i, o ->
                    Surface(
                        onClick = { onPick(o.value) },
                        modifier = Modifier.fillMaxWidth().then(if (i == current.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(o.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (i == current) Text("✓ now", color = Jc.Focus, fontSize = 14.sp)
                        }
                    }
                }
                Text("▲ ▼ move · OK choose · Back keeps it", color = Jc.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            }
        }
    }
}

/**
 * Every theme as a small preview card, in groups. The app takes on the focused theme as the cursor
 * moves (nothing is kept until OK); Back puts the theme it had back. Gemstone's colours sit under
 * its card.
 */
@Composable
internal fun ThemeGallery(onDismiss: () -> Unit) {
    val prefs = Jdvr.prefs
    val startTheme = remember { prefs.theme }
    val startGem = remember { prefs.gem }

    // The cursor's theme (and for Gemstone perhaps one of its colours), shown at once.
    fun preview(
        theme: String,
        gem: String,
    ) {
        Jc.palette = paletteFor(theme, gem)
    }

    fun cancel() {
        Jc.palette = paletteFor(startTheme, startGem)
        onDismiss()
    }

    fun keep(
        theme: String,
        gem: String?,
    ) {
        if (gem != null) prefs.changeGem(gem)
        prefs.changeTheme(theme)
        onDismiss()
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (first.tryFocus()) {
                // The dialog's own first focus (the first card) can land after ours and preview it:
                // the cursor is on the current theme, so preview that.
                delay(150)
                preview(startTheme, startGem)
                return@LaunchedEffect
            }
            delay(40)
        }
    }
    val groups =
        listOf(
            "Dark" to Palettes.filter { !it.light && !it.style.menuHome },
            "Light" to Palettes.filter { it.light },
            "SageTV · Home becomes their menu" to Palettes.filter { it.style.menuHome },
        )
    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .screenBackground()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Theme", color = Jc.Title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("The app changes as you move · OK keeps it · Back puts ${paletteFor(startTheme).name} back", color = Jc.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEachIndexed { gi, (name, list) ->
                    Column(if (gi == 0) Modifier.fillMaxWidth() else Modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name.uppercase(), color = Jc.Muted, fontSize = 12.sp, letterSpacing = 1.5.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            list.forEach { p ->
                                ThemeCard(
                                    p,
                                    current = p.key == startTheme,
                                    modifier = if (p.key == startTheme) Modifier.focusRequester(first) else Modifier,
                                    onFocused = { preview(p.key, startGem) },
                                    onClick = { keep(p.key, null) },
                                    gemSwatches =
                                        if (p.style == UiStyle.Gem) {
                                            { gemKey -> preview(p.key, gemKey) }
                                        } else {
                                            null
                                        },
                                    onGemPicked = { keep(p.key, it) },
                                    gemNow = if (startTheme == p.key) startGem else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(
    p: Palette,
    current: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    gemSwatches: ((String) -> Unit)?,
    onGemPicked: (String) -> Unit,
    gemNow: String?,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(118.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(
            onClick = onClick,
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .onFocusChangedCompat {
                        focused = it
                        if (it) onFocused()
                    },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = p.ink, focusedContainerColor = p.ink),
            border =
                ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, Jc.Text.copy(alpha = 0.15f))),
                    focusedBorder = Border(BorderStroke(3.dp, Jc.Text)),
                ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
        ) {
            ThemeSample(p)
            if (current) Text("✓", color = p.focus, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp))
        }
        Text(p.name, color = if (focused) Jc.Text else Jc.Muted, fontSize = 12.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (gemSwatches != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Gems.forEach { g ->
                    var on by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onGemPicked(g.key) },
                        modifier =
                            Modifier.size(18.dp).onFocusChangedCompat {
                                on = it
                                if (it) gemSwatches(g.key)
                            },
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = g.color, focusedContainerColor = g.color),
                        border =
                            ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(if (g.key == gemNow) 2.dp else 0.dp, Jc.Muted)),
                                focusedBorder = Border(BorderStroke(2.dp, Jc.Text)),
                            ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.25f),
                    ) {}
                }
            }
        }
    }
}

/** A theme in miniature: its background, side menu, two lines of text, a panel and its focus. */
@Composable
private fun ThemeSample(p: Palette) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                if (p.style == UiStyle.Sage) {
                    drawRect(Brush.radialGradient(listOf(Color(0xFF2A5BB0), Color(0xFF133779), Color(0xFF0A1D4A)), center = Offset(size.width * 0.15f, size.height * 0.1f), radius = size.width))
                }
                if (p.style == UiStyle.NextPvr) {
                    drawRect(Brush.radialGradient(listOf(Color(0xFF0D3A78), Color(0xFF061C45), Color(0xFF020A1C)), center = Offset(size.width * 0.3f, size.height * 0.2f), radius = size.width))
                }
            },
    ) {
        Box(Modifier.width(16.dp).fillMaxHeight().background(p.rail))
        Column(Modifier.padding(start = 24.dp, top = 9.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(p.text),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.muted),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(p.panel2),
            )
            when (p.style) {
                UiStyle.Sage -> {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .sageGloss(),
                    )
                }

                UiStyle.NextPvr -> {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .nextPvrGloss(),
                    )
                }

                UiStyle.Gem -> {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(11.dp)
                            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.03f))), RoundedCornerShape(2.dp))
                            .border(1.dp, if (Jc.Style == UiStyle.Gem) Jc.Focus else p.focus, RoundedCornerShape(2.dp)),
                    )
                }

                else -> {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(p.focus),
                    )
                }
            }
        }
    }
}
