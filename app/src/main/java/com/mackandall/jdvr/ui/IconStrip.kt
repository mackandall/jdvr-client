package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.Screen
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Library
import java.util.UUID

/** Width of the icon strip; the screen beside it starts after this. */
val IconStripWidth = 58.dp

/** What the strip has focused: its menu key and label (Channels JDVR's Home heading shows the label). */
object IconStripFocus {
    var key by mutableStateOf<String?>(null)
    var label by mutableStateOf<String?>(null)
}

/**
 * The menu's places as icons down the left (Settings › Menu › Icon strip, kept per theme): the same
 * list as the slide-out menu ([menuPlaces]), in its order, each icon level with its entry in the
 * menu so ◄ from an icon opens the menu right on it. The libraries are one icon while grouped (OK
 * opens the menu with them), or one each. Settings sits at the bottom, as in the menu. Too many to
 * fit: it scrolls.
 */
@Composable
fun IconStrip(
    selected: Screen,
    libraryId: UUID?,
    libraries: List<Library>,
    showRecorded: Boolean,
    showTrash: Boolean,
    problems: Int,
    onSelect: (Screen) -> Unit,
    onSelectLibrary: (UUID) -> Unit,
    onOpenLibraries: () -> Unit,
) {
    val rights = deviceRights()
    val places = menuPlaces(libraries, showRecorded, showTrash, problems, selected, rights, librariesOpen = false)
    val set = iconSetFor(Jdvr.prefs.menuIconSet) ?: IconSets.first()
    // Level with the menu: its list starts 66 dp down and its rows are as tall as last drawn.
    val density = LocalDensity.current
    val row = if (RailMetrics.rowPx > 0) with(density) { RailMetrics.rowPx.toDp() } else 40.dp
    fun isOn(p: MenuPlace) =
        when {
            p.kind == PlaceKind.Group -> selected == Screen.Library
            p.library != null -> selected == Screen.Library && p.library.id == libraryId
            else -> p.screen == selected
        }
    // ◄ from the screen lands on the current place's icon, wherever focus came from.
    val here = remember { FocusRequester() }
    val hasHere = selected == Screen.Settings || places.any { isOn(it) }
    Column(
        Modifier
            .focusProperties { onEnter = { if (hasHere) here.requestFocus() } }
            .focusGroup()
            .width(IconStripWidth)
            .fillMaxHeight()
            .background(Jc.Rail)
            .padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 66.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            places.forEach { p ->
                if (p.divider) Box(Modifier.height(5.dp))
                val on = isOn(p)
                Box(Modifier.height(row), contentAlignment = Alignment.Center) {
                    StripIcon(p.icon, p.label, p.key, on, p.count, set, if (on) Modifier.focusRequester(here) else Modifier) {
                        when {
                            p.kind == PlaceKind.Group -> onOpenLibraries()
                            p.library != null -> onSelectLibrary(p.library.id)
                            p.screen != null -> onSelect(p.screen)
                        }
                    }
                }
            }
        }
        StripIcon(MenuIcon.Settings, Screen.Settings.label, "settings", selected == Screen.Settings, 0, set, if (selected == Screen.Settings) Modifier.focusRequester(here) else Modifier) { onSelect(Screen.Settings) }
    }
}

@Composable
private fun StripIcon(
    icon: MenuIcon,
    label: String,
    key: String,
    selected: Boolean,
    count: Int,
    set: IconSet,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val round = Jc.Style == UiStyle.Channels
    Box {
        Surface(
            onClick = onClick,
            modifier =
                modifier.size(38.dp).onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        IconStripFocus.key = key
                        IconStripFocus.label = label
                    } else if (IconStripFocus.key == key) {
                        IconStripFocus.key = null
                        IconStripFocus.label = null
                    }
                },
            shape = ClickableSurfaceDefaults.shape(if (round) CircleShape else RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = Jc.FocusFill),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val c = if (focused) Jc.Text else if (selected) Jc.Focus else Jc.Muted
                MenuGlyph(icon, set, 20.dp, c, Jc.Focus)
            }
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-3).dp)
                    .height(16.dp)
                    .widthIn(min = 16.dp)
                    .background(Jc.Amber, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$count", color = Jc.OnAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
