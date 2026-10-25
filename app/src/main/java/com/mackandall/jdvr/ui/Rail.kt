package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Prefs
import kotlinx.coroutines.launch
import java.util.UUID
import com.mackandall.jdvr.data.Library

/** The libraries in their menu order (Settings › Menu); ones not placed yet follow, as the server lists them. */
fun List<Library>.inMenuOrder(): List<Library> {
    val order = Jdvr.prefs.libraryOrder
    return sortedBy { l -> order.indexOf(l.id.toString()).let { if (it < 0) Int.MAX_VALUE else it } }
}

private enum class Kind { Item, Group, Child }

private data class Entry(
    val key: String,
    val label: String,
    val on: Boolean,
    val kind: Kind,
    val divider: Boolean = false,
    val icon: MenuIcon? = null,
    val click: () -> Unit,
)

/**
 * The left rail, opened over the screen (dimmed behind it); focus starts on the current section.
 * Its entries and their order come from Settings › Menu. Settings, Sign out and Exit are a row of
 * icons pinned under the list (or its last entries); the libraries sit under one Libraries entry
 * that opens and closes (OK or ►; ◄ closes it), or each on its own line. Sign out takes a second
 * press, as getting back in means signing in again.
 */
@Composable
fun Rail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    onSignOut: () -> Unit,
    onSwitchServer: () -> Unit = {},
    onExit: () -> Unit,
    libraries: List<Library> = emptyList(),
    selectedLibrary: UUID? = null,
    onSelectLibrary: (Library) -> Unit = {},
    onDismiss: () -> Unit = {},
    // True while Right belongs to the rail (along the icon row, or opening Libraries).
    onKeepsRight: (Boolean) -> Unit = {},
    // Recorded Shows: only on a server that has Live TV or recordings.
    showRecorded: Boolean = true,
    showTrash: Boolean = false,
) {
    val prefs = Jdvr.prefs
    val rights = deviceRights()
    var open by remember {
        mutableStateOf(
            when (prefs.librariesStart) {
                Prefs.OPEN -> true
                Prefs.CLOSED -> false
                else -> prefs.librariesOpen
            },
        )
    }
    fun setOpen(v: Boolean) {
        open = v
        prefs.changeLibrariesOpen(v)
    }
    var signOutArmed by remember { mutableStateOf(false) }
    LaunchedEffect(signOutArmed) {
        if (signOutArmed) {
            kotlinx.coroutines.delay(4000)
            signOutArmed = false
        }
    }
    fun signOut() {
        if (signOutArmed) onSignOut() else signOutArmed = true
    }
    val libs = libraries.inMenuOrder()
    val icons = prefs.menuIcons
    val entries =
        buildList {
            prefs.menuOrder.filter { it == "home" || it !in prefs.hiddenMenu }.forEach { k ->
                if (k == "libraries") {
                    if (libs.isEmpty()) return@forEach
                    if (prefs.librariesGrouped) {
                        add(Entry(k, "Libraries", !open && selected == Screen.Library, Kind.Group, icon = MenuIcon.Libraries) { setOpen(!open) })
                        if (open) libs.forEach { l -> add(Entry("lib:${l.id}", l.name, selected == Screen.Library && l.id == selectedLibrary, Kind.Child, icon = l.menuIcon()) { onSelectLibrary(l) }) }
                    } else {
                        libs.forEachIndexed { i, l ->
                            add(Entry("lib:${l.id}", l.name, selected == Screen.Library && l.id == selectedLibrary, Kind.Item, divider = i == 0, icon = l.menuIcon()) { onSelectLibrary(l) })
                        }
                    }
                } else {
                    val s = Screen.entries.firstOrNull { it.key == k } ?: return@forEach
                    if (s == Screen.RecordedShows && !showRecorded) return@forEach
                    if (s == Screen.Trash && !showTrash) return@forEach
                    // What this TV may not do (JellyDVR Space's device rights) isn't in its menu.
                    if (s == Screen.LiveTv && !rights.liveTv) return@forEach
                    if (s == Screen.Series && !rights.record) return@forEach
                    // A line under the libraries when they're listed one by one.
                    val afterLibs = lastOrNull()?.let { it.kind == Kind.Item && it.key.startsWith("lib:") } == true
                    add(Entry(k, s.label, s == selected, Kind.Item, divider = afterLibs, icon = s.menuIcon()) { onSelect(s) })
                }
            }
            if (!icons) {
                add(Entry("settings", Screen.Settings.label, selected == Screen.Settings, Kind.Item, divider = true, icon = MenuIcon.Settings) { onSelect(Screen.Settings) })
                add(Entry("switch", "Switch server", false, Kind.Item, icon = MenuIcon.Server) { onSwitchServer() })
                add(Entry("signout", if (signOutArmed) "Press again to sign out" else "Sign out", false, Kind.Item, icon = MenuIcon.Profile) { signOut() })
                add(Entry("exit", "Exit", false, Kind.Item, icon = MenuIcon.Exit, click = onExit))
            }
        }
    // One focus handle per entry, kept by key, so opening Libraries doesn't lose focus.
    val reqs = remember { mutableMapOf<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    val gear = req("gear")
    val user = req("user")
    val serverIcon = req("server")
    val power = req("power")
    val last = entries.lastOrNull()?.let { req(it.key) } ?: FocusRequester.Default
    val current =
        when {
            icons && selected == Screen.Settings -> gear
            else -> entries.firstOrNull { it.on }?.let { req(it.key) } ?: entries.firstOrNull()?.let { req(it.key) } ?: gear
        }
    fun slot(i: Int): Modifier =
        Modifier.focusRequester(req(entries[i].key)).focusProperties {
            up = if (i > 0) req(entries[i - 1].key) else FocusRequester.Cancel
            down = if (i < entries.size - 1) req(entries[i + 1].key) else if (icons) gear else FocusRequester.Cancel
            left = FocusRequester.Cancel
        }

    var focusedKey by remember { mutableStateOf<String?>(null) }

    // Icons (Settings › Menu): the set, and each entry's size by its distance from the focused one
    // (the row of icons under the list counts as the entry after the last).
    val iconSet = iconSetFor(prefs.menuIconSet)
    val growth = menuIconGrowth(prefs.menuIconSize)
    val focusedAt =
        when (focusedKey) {
            "gear", "user", "power" -> entries.size
            else -> entries.indexOfFirst { it.key == focusedKey }
        }
    fun iconScale(i: Int): Float = if (focusedAt < 0) 1f else menuIconScale(prefs.menuIconMotion, growth, kotlin.math.abs(i - focusedAt))
    val itemWidth = if (iconSet != null) 216.dp else 192.dp
    LaunchedEffect(focusedKey, open) { onKeepsRight(focusedKey == "gear" || focusedKey == "user" || (focusedKey == "libraries" && !open)) }
    DisposableEffect(Unit) { onDispose { onKeepsRight(false) } }

    var hadFocus by remember { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Keep asking until the rail really has focus: a screen still settling (a grid loading,
    // restoring its focus) can take it back, and then the rail would never notice focus leaving.
    LaunchedEffect(Unit) {
        repeat(30) {
            if (hadFocus) return@LaunchedEffect
            runCatching { current.requestFocus() }
            kotlinx.coroutines.delay(50)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            modifier =
                Modifier
                    .onFocusChanged {
                        // Focus moving between the icons and the list leaves the rail for an instant:
                        // only a rail still without focus a moment later is done with.
                        if (hadFocus && !it.hasFocus) {
                            scope.launch {
                                kotlinx.coroutines.delay(80)
                                if (!railHasFocus) onDismiss()
                            }
                        }
                        railHasFocus = it.hasFocus
                        if (it.hasFocus) hadFocus = true
                    }.width(itemWidth + 28.dp)
                    .fillMaxHeight()
                    .background(Jc.Rail)
                    .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.height(40.dp).padding(start = 10.dp, top = 8.dp))
            // One scrolling list (no boundary for focus to cross). The scroll area clips at its edges,
            // so it keeps a little room inside them: the first or last entry, focused (a touch
            // bigger, with its ring), isn't cut off.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entries.forEachIndexed { i, e ->
                    key(e.key) {
                        if (e.divider) Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).width(168.dp).height(1.dp).background(Jc.Line))
                        val m = slot(i).onFocusChanged { if (it.isFocused) focusedKey = e.key }
                        when (e.kind) {
                            Kind.Item -> RailItem(e.label, e.on, m, width = itemWidth, icon = e.icon, iconSet = iconSet, iconScale = iconScale(i), onClick = e.click)
                            Kind.Child ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.padding(start = 16.dp, end = 10.dp).width(2.dp).height(36.dp).background(Jc.Line))
                                    RailItem(e.label, e.on, m, width = itemWidth - 28.dp, small = true, icon = e.icon, iconSet = iconSet, iconScale = iconScale(i), onClick = e.click)
                                }
                            Kind.Group ->
                                RailItem(
                                    e.label,
                                    e.on,
                                    m.onPreviewKeyEvent { k ->
                                        // ► opens the group, ◄ closes it.
                                        if (k.type != KeyEventType.KeyDown) {
                                            false
                                        } else if (k.key == Key.DirectionRight && !open) {
                                            setOpen(true)
                                            true
                                        } else if (k.key == Key.DirectionLeft && open) {
                                            setOpen(false)
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    width = itemWidth,
                                    icon = e.icon,
                                    iconSet = iconSet,
                                    iconScale = iconScale(i),
                                    trailing = {
                                        if (!open) Text("${libs.size}", fontSize = 13.sp, color = it, modifier = Modifier.padding(end = 8.dp))
                                        StrokeIcon(if (open) CHEVRON_DOWN else CHEVRON_RIGHT, 16.dp, it)
                                    },
                                    onClick = e.click,
                                )
                        }
                    }
                }
            }
            if (icons) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).width(168.dp).height(1.dp).background(Jc.Line))
                // The focused icon's name (the line keeps its height so the row doesn't move).
                Text(
                    when (focusedKey) {
                        "gear" -> "Settings"
                        "server" -> "Switch server"
                        "user" -> if (signOutArmed) "Press again to sign out" else "Sign out"
                        "power" -> "Exit JDVR"
                        else -> ""
                    },
                    color = if (focusedKey == "user" && signOutArmed) Jc.Recording else Jc.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.height(24.dp).padding(start = 10.dp),
                )
                Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RailIcon(
                        GEAR,
                        MenuIcon.Settings,
                        iconSet,
                        iconScale(entries.size).takeIf { focusedKey == "gear" } ?: 1f,
                        "Settings",
                        selected == Screen.Settings,
                        Modifier.focusRequester(gear).focusProperties {
                            up = last
                            down = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                        }.onFocusChanged { if (it.isFocused) focusedKey = "gear" },
                    ) { onSelect(Screen.Settings) }
                    RailIcon(
                        SERVER,
                        MenuIcon.Server,
                        iconSet,
                        iconScale(entries.size).takeIf { focusedKey == "server" } ?: 1f,
                        "Switch server",
                        false,
                        Modifier.focusRequester(serverIcon).focusProperties {
                            up = last
                            down = FocusRequester.Cancel
                        }.onFocusChanged { if (it.isFocused) focusedKey = "server" },
                        onClick = onSwitchServer,
                    )
                    RailIcon(
                        USER,
                        MenuIcon.Profile,
                        iconSet,
                        iconScale(entries.size).takeIf { focusedKey == "user" } ?: 1f,
                        "Sign out",
                        false,
                        Modifier.focusRequester(user).focusProperties {
                            up = last
                            down = FocusRequester.Cancel
                        }.onFocusChanged {
                            if (it.isFocused) focusedKey = "user" else signOutArmed = false
                        },
                        onClick = ::signOut,
                    )
                    RailIcon(
                        POWER,
                        MenuIcon.Exit,
                        iconSet,
                        iconScale(entries.size).takeIf { focusedKey == "power" } ?: 1f,
                        "Exit",
                        false,
                        Modifier.focusRequester(power).focusProperties {
                            up = last
                            down = FocusRequester.Cancel
                        }.onFocusChanged { if (it.isFocused) focusedKey = "power" },
                        onClick = onExit,
                    )
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    width: Dp = 192.dp,
    small: Boolean = false,
    icon: MenuIcon? = null,
    iconSet: IconSet? = null,
    iconScale: Float = 1f,
    trailing: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // The row's own height bounds its icon: a magnified one never pokes out of it (nor does the
    // spring's overshoot), whatever the font size.
    var rowHeight by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val base = if (small) 16.dp else 18.dp
    val fit = with(density) { if (rowHeight == 0) 1f else ((rowHeight - 6.dp.toPx()) / base.toPx()).coerceAtLeast(1f) }
    val grown by androidx.compose.animation.core.animateFloatAsState(
        iconScale.coerceAtMost(fit),
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "menuIcon",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.width(width).onFocusChanged { focused = it.isFocused }.onSizeChanged { rowHeight = it.height },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Jc.Panel2 else Jc.Rail,
                focusedContainerColor = Jc.FocusFill,
                contentColor = if (selected) Jc.Text else Jc.Muted,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        val pad = if (small) 7.dp else 8.dp
        val withIcon = icon != null && iconSet != null
        Row(Modifier.padding(start = if (withIcon) 10.dp else 16.dp, end = 12.dp, top = pad, bottom = pad), verticalAlignment = Alignment.CenterVertically) {
            if (withIcon) {
                Box(Modifier.width(base + 10.dp), contentAlignment = Alignment.Center) {
                    val s = grown.coerceAtMost(fit)
                    MenuGlyph(
                        icon!!,
                        iconSet!!,
                        base,
                        if (focused) Jc.Focus else if (selected) Jc.Text else Jc.Muted,
                        Jc.Focus,
                        Modifier.graphicsLayer {
                            scaleX = s
                            scaleY = s
                        },
                    )
                }
                Box(Modifier.width(10.dp))
            }
            Text(label, fontSize = if (small) 15.sp else 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
            trailing?.invoke(if (focused || selected) Jc.Text else Jc.Muted)
        }
    }
}

/** A round icon button in the rail's bottom row (its name shows above the row while focused). */
@Composable
private fun RailIcon(
    paths: List<String>,
    icon: MenuIcon,
    iconSet: IconSet?,
    // Inside its circle the glyph grows by at most a quarter.
    iconScale: Float,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.size(34.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Jc.Panel2 else Color.Transparent,
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Muted,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus), shape = CircleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        val grown by androidx.compose.animation.core.animateFloatAsState(
            iconScale.coerceAtMost(1.25f),
            androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
            label = "railIcon",
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (iconSet == null) {
                StrokeIcon(paths, 18.dp, if (focused) Jc.Text else Jc.Muted)
            } else {
                val s = grown.coerceAtMost(1.25f)
                MenuGlyph(icon, iconSet, 18.dp, if (focused) Jc.Text else Jc.Muted, Jc.Focus, Modifier.graphicsLayer { scaleX = s; scaleY = s })
            }
        }
    }
}

/** A line icon drawn from 24×24 SVG path data. */
@Composable
private fun StrokeIcon(
    paths: List<String>,
    size: Dp,
    color: Color,
) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size)) {
        val f = this.size.width / 24f
        scale(f, f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            parsed.forEach { drawPath(it, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

private val GEAR =
    listOf(
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )
private val SERVER = listOf("M5 4h14a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 19 10H5a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 5 4z", "M5 14h14a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 19 20H5a1.5 1.5 0 0 1-1.5-1.5v-3A1.5 1.5 0 0 1 5 14z", "M7.5 7h.01M7.5 17h.01M11 7h5.5M11 17h5.5")
private val USER = listOf("M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M4 21a8 8 0 0 1 16 0")
private val POWER = listOf("M12 2v10", "M18.4 6.6a9 9 0 1 1-12.8 0")
private val CHEVRON_RIGHT = listOf("M9 18L15 12L9 6")
private val CHEVRON_DOWN = listOf("M6 9L12 15L18 9")
