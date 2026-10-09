package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.BuildConfig
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.Prefs

/**
 * Settings: things set once for the whole app. Actions that belong to what's on screen (Record,
 * Keep, Skip…) stay on the screens. OK on a row steps through its choices.
 */
@Composable
fun SettingsScreen(
    entry: FocusRequester,
    libraries: List<com.mackandall.jdvr.data.LiveTvRepository.Library> = emptyList(),
) {
    val prefs = Jdvr.prefs
    var menuPage by remember { mutableStateOf(false) }
    var cameBack by remember { mutableStateOf(false) }
    val menuButton = remember { FocusRequester() }
    if (menuPage) {
        androidx.activity.compose.BackHandler {
            menuPage = false
            cameBack = true
        }
        MenuSettings(libraries)
        return
    }
    // Back from the Menu page: focus where it was opened from.
    androidx.compose.runtime.LaunchedEffect(Unit) { if (cameBack) runCatching { menuButton.requestFocus() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(6)
        Section("Appearance")
        Choice(
            label = "Theme",
            help = "The colours of the whole app. Ode to Emby's Apple is light: frosted panels on a soft backdrop.",
            value = paletteFor(prefs.theme).name,
            modifier = Modifier.focusRequester(entry),
        ) { prefs.changeTheme(Palettes[(Palettes.indexOf(paletteFor(prefs.theme)) + 1) % Palettes.size].key) }
        if (paletteFor(prefs.theme).light) {
            Choice(
                label = "Backdrop",
                help = "The soft colour behind the screens.",
                value = washFor(prefs.wash).name,
            ) { prefs.changeWash(Washes[(Washes.indexOf(washFor(prefs.wash)) + 1) % Washes.size].key) }
        }
        Section("Guide")
        Choice(
            label = "Programme details",
            help = "Beside the guide, or above it so the guide uses the full width.",
            value = if (prefs.guideInfo == Prefs.TOP) "Above the guide" else "Beside the guide",
        ) { prefs.changeGuideInfo(if (prefs.guideInfo == Prefs.TOP) Prefs.SIDE else Prefs.TOP) }
        Choice(
            label = "Background art",
            help = "Rest on a programme and its picture fades in behind the guide, which turns see-through. Off keeps the plain dark guide.",
            value = if (prefs.guideArtSeconds == 0) "Off" else "After ${prefs.guideArtSeconds} s",
        ) { prefs.changeGuideArtSeconds(if (prefs.guideArtSeconds >= 5) 0 else if (prefs.guideArtSeconds == 0) 2 else prefs.guideArtSeconds + 1) }
        Section("Recordings")
        Choice(
            label = "Commercials",
            help = "Found by Comskip, also while a programme is still recording.",
            value = skipLabel(prefs.commercialSkip),
        ) { prefs.changeCommercialSkip(next(prefs.commercialSkip)) }
        Choice(
            label = "Intros, recaps and previews",
            help = "Found by Intro Skipper.",
            value = skipLabel(prefs.introSkip),
        ) { prefs.changeIntroSkip(next(prefs.introSkip)) }
        Section("Menu")
        Choice(
            label = "Menu entries",
            help = "Their order, which are shown, the Libraries group, and where Settings, Sign out and Exit go.",
            value = "Arrange…",
            modifier = Modifier.focusRequester(menuButton),
        ) { menuPage = true }
        Section("Weather on Home")
        Choice(
            label = "Weather",
            help = "Today's weather and a sky to match behind Home (from Open-Meteo: only your town is sent).",
            value = if (prefs.weatherOn) "Shown" else "Off",
        ) { prefs.changeWeatherOn(!prefs.weatherOn) }
        Choice(
            label = "Temperature",
            help = "Degrees Fahrenheit or Celsius.",
            value = if (prefs.fahrenheit) "°F" else "°C",
        ) { prefs.changeFahrenheit(!prefs.fahrenheit) }
        TownRow()
        Section("Playback")
        Choice(
            label = "Subtitles",
            help = "Closed captions or subtitles when something starts, if it has any. The player's Subtitles button switches them any time.",
            value = if (prefs.subtitles == Prefs.ON) "On when available" else "Off",
        ) { prefs.changeSubtitles(if (prefs.subtitles == Prefs.ON) Prefs.OFF else Prefs.ON) }
        Choice(
            label = "Info box at the top",
            help = "While a recording, show or movie plays: what it is, when it was recorded and aired, and what's next, at the top whenever the info bar shows. The player's Info button opens the full details either way.",
            value = if (prefs.infoBoxTop) "On" else "Off",
        ) { prefs.changeInfoBoxTop(!prefs.infoBoxTop) }
        Section("Remote")
        Choice(
            label = "Hold OK on a poster or episode",
            help = "Off: holding OK is just a press. Quick actions: play, from the start, details, mark watched, and for recordings favourites, archive and delete. The Menu key, where the remote has one, does the same.",
            value =
                when (prefs.holdOk) {
                    Prefs.HOLD_MENU -> "Quick actions menu"
                    Prefs.HOLD_DETAILS -> "Details page"
                    else -> "Off"
                },
        ) {
            prefs.changeHoldOk(
                when (prefs.holdOk) {
                    Prefs.HOLD_OFF -> Prefs.HOLD_MENU
                    Prefs.HOLD_MENU -> Prefs.HOLD_DETAILS
                    else -> Prefs.HOLD_OFF
                },
            )
        }
        Section("About")
        Text(
            "JDVR ${BuildConfig.VERSION_NAME} · signed in as ${Jdvr.session.userName ?: "?"} on ${Jdvr.session.serverUrl ?: "?"}",
            color = Jc.Muted,
            fontSize = 15.sp,
        )
    }
}

private fun skipLabel(v: String) =
    when (v) {
        Prefs.AUTO -> "Skip automatically"
        Prefs.OFF -> "Don't skip"
        else -> "Ask (show a Skip button)"
    }

private fun next(v: String) =
    when (v) {
        Prefs.ASK -> Prefs.AUTO
        Prefs.AUTO -> Prefs.OFF
        else -> Prefs.ASK
    }

@Composable
private fun Section(title: String) {
    Text(title, color = Jc.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Choice(
    label: String,
    help: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(0.8f),
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
            Text(value, color = Jc.Focus, fontSize = 16.sp, modifier = Modifier.width(260.dp))
        }
    }
}

/** The weather's town: type it (on-screen keyboard), Find looks it up and keeps the best match. */
@Composable
private fun TownRow() {
    val prefs = Jdvr.prefs
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Town: " + (prefs.weatherPlace?.name ?: "not set"), color = Jc.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Jc.Text, fontSize = 16.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Jc.Focus),
                modifier =
                    Modifier
                        .weight(1f)
                        .border(BorderStroke(2.dp, Jc.Line), RoundedCornerShape(10.dp))
                        .background(Jc.Panel2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Town, e.g. Atlanta or Springfield, IL", color = Jc.Muted, fontSize = 16.sp)
                    inner()
                },
            )
            Button("Find", primary = true) {
                val q = text.trim()
                if (q.isEmpty()) return@Button
                scope.launch {
                    note =
                        runCatching { com.mackandall.jdvr.data.Weather.find(q) }.getOrNull()?.let {
                            prefs.changeWeatherPlace(it)
                            text = ""
                            "Weather for ${it.name}"
                        } ?: "Couldn't find \"$q\" (check the spelling, or add the state: Springfield, IL)"
                }
            }
        }
        note?.let { Text(it, color = Jc.Muted, fontSize = 14.sp) }
    }
}

/**
 * Settings › Menu: the menu's entries in order. OK picks one up, ▲▼ move it, OK puts it down (Back
 * puts it back where it was); the button beside each shows or hides it. Libraries move as a group,
 * and each library within it.
 */
@Composable
private fun MenuSettings(libraries: List<com.mackandall.jdvr.data.LiveTvRepository.Library>) {
    val prefs = Jdvr.prefs
    val libs = libraries.inMenuOrder()
    data class MRow(
        val key: String,
        val label: String,
        val child: Boolean,
    )
    val rows =
        prefs.menuOrder.flatMap { k ->
            if (k == "libraries") {
                listOf(MRow(k, if (prefs.librariesGrouped) "Libraries" else "Libraries (each on its own line)", false)) +
                    libs.map { MRow("lib:${it.id}", it.name, true) }
            } else {
                listOf(MRow(k, com.mackandall.jdvr.Screen.entries.first { s -> s.key == k }.label, false))
            }
        }
    var lifted by remember { mutableStateOf<String?>(null) }
    // The order before the pick-up, for Back.
    var before by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun move(
        key: String,
        by: Int,
    ) {
        if (key.startsWith("lib:")) {
            val ids = libs.map { it.id.toString() }.toMutableList()
            val i = ids.indexOf(key.removePrefix("lib:"))
            val j = i + by
            if (i < 0 || j !in ids.indices) return
            ids.add(j, ids.removeAt(i))
            prefs.changeLibraryOrder(ids)
        } else {
            val order = prefs.menuOrder.toMutableList()
            val i = order.indexOf(key)
            val j = i + by
            if (i < 0 || j !in order.indices) return
            order.add(j, order.removeAt(i))
            prefs.changeMenuOrder(order)
        }
    }
    androidx.activity.compose.BackHandler(enabled = lifted != null) {
        before?.let { (order, libOrder) ->
            prefs.changeMenuOrder(order)
            prefs.changeLibraryOrder(libOrder)
        }
        lifted = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("› Menu", color = Jc.Muted, fontSize = 20.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(
            "OK picks an entry up, ▲▼ move it, OK puts it down (Back puts it back). The button on the right shows or hides it.",
            color = Jc.Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        rows.forEachIndexed { i, r ->
            androidx.compose.runtime.key(r.key) {
                val isLifted = lifted == r.key
                val hidden =
                    when {
                        r.key == "home" -> false
                        r.child -> r.key.removePrefix("lib:") in prefs.hiddenLibraries
                        else -> r.key in prefs.hiddenMenu
                    }
                Row(
                    Modifier.fillMaxWidth(0.7f).padding(start = if (r.child) 36.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = {
                            if (lifted == null) {
                                before = prefs.menuOrder to libs.map { it.id.toString() }
                                lifted = r.key
                            } else if (isLifted) {
                                lifted = null
                            }
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                .onPreviewKeyEvent { e ->
                                    // While picked up: ▲▼ move it; nothing else moves focus away.
                                    if (!isLifted || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.DirectionUp -> {
                                            move(r.key, -1)
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            move(r.key, 1)
                                            true
                                        }
                                        Key.DirectionLeft, Key.DirectionRight -> true
                                        else -> false
                                    }
                                },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors =
                            ClickableSurfaceDefaults.colors(
                                containerColor = if (isLifted) Jc.FocusFill else Jc.Panel,
                                focusedContainerColor = Jc.FocusFill,
                                contentColor = if (hidden) Jc.Muted else Jc.Text,
                                focusedContentColor = Jc.Text,
                            ),
                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(if (isLifted) 3.dp else 2.dp, Jc.Focus))),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isLifted) 1.03f else 1.01f),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isLifted) "▲▼" else "⋮⋮", color = if (isLifted) Jc.Focus else Jc.Line, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                            Text(r.label, fontSize = 18.sp, modifier = Modifier.weight(1f))
                            if (isLifted) Text("Moving · OK to place", color = Jc.Focus, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (r.key == "home") {
                        Text("Always shown", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.width(150.dp).padding(start = 14.dp))
                    } else {
                        Surface(
                            onClick = {
                                if (lifted != null) return@Surface
                                if (r.child) prefs.toggleLibrary(r.key.removePrefix("lib:")) else prefs.toggleMenu(r.key)
                            },
                            modifier = Modifier.width(150.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = if (hidden) Jc.Muted else Jc.Focus, focusedContentColor = Jc.Text),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                        ) {
                            Text(if (hidden) "Hidden" else "Shown", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                        }
                    }
                }
            }
        }
        Section("Options")
        Choice(
            label = "Libraries in the menu",
            help = "One Libraries entry that opens and closes (OK or ►; ◄ closes it), or each library on its own line.",
            value = if (prefs.librariesGrouped) "Grouped" else "Each on its own line",
        ) { prefs.changeLibrariesGrouped(!prefs.librariesGrouped) }
        if (prefs.librariesGrouped) {
            Choice(
                label = "Libraries group starts",
                help = "How the group is when the menu opens.",
                value =
                    when (prefs.librariesStart) {
                        Prefs.OPEN -> "Open"
                        Prefs.CLOSED -> "Closed"
                        else -> "As last left"
                    },
            ) {
                prefs.changeLibrariesStart(
                    when (prefs.librariesStart) {
                        Prefs.LAST -> Prefs.CLOSED
                        Prefs.CLOSED -> Prefs.OPEN
                        else -> Prefs.LAST
                    },
                )
            }
        }
        Choice(
            label = "Settings, Sign out and Exit",
            help = "A row of icons under the menu (▼ from the last entry), or the last three entries.",
            value = if (prefs.menuIcons) "Icons under the menu" else "Last in the list",
        ) { prefs.changeMenuIcons(!prefs.menuIcons) }
        var armed by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(armed) {
            if (armed) {
                kotlinx.coroutines.delay(4000)
                armed = false
            }
        }
        Choice(
            label = "Reset the menu",
            help = "The standard order, with every entry and library shown.",
            value = if (armed) "Press again to reset" else "Reset",
        ) {
            if (armed) {
                prefs.resetMenu()
                armed = false
            } else {
                armed = true
            }
        }
    }
}
