package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.Prefs
import com.mackandall.jdvr.data.attempt
import kotlinx.coroutines.launch

/**
 * Settings: things set once for the whole app. Actions that belong to what's on screen (Record,
 * Keep, Skip…) stay on the screens. OK on a row steps through its choices.
 */
@Composable
fun SettingsScreen(
    entry: FocusRequester,
    libraries: List<Library> = emptyList(),
    // Straight to the menu's page (SageTV's Setup › Menu order).
    openMenuPage: Boolean = false,
    onSwitchServer: () -> Unit = {},
) {
    val prefs = Jdvr.prefs
    var menuPage by remember { mutableStateOf(openMenuPage) }
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
    androidx.compose.runtime.LaunchedEffect(Unit) { if (cameBack) menuButton.tryFocus() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", color = Jc.Title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(6)
        Section("Appearance")
        // OK on Theme opens the gallery (Gemstone's colours are on its card there).
        var gallery by remember { mutableStateOf(false) }
        Options(
            label = "Theme",
            help = "The colours of the whole app. Ode to Emby's Apple is light: frosted panels on a soft backdrop. Paper and Daylight are light and plain. SageTV and SageTV Gemstone also change Home into their main menu.",
            options = Palettes.map { Opt(it.key, it.name + if (it.style == UiStyle.Gem) " · ${gemFor(prefs.gem).name}" else "") },
            selected = paletteFor(prefs.theme).key,
            modifier = Modifier.focusRequester(entry),
            onOpen = { gallery = true },
        ) { prefs.changeTheme(it) }
        if (gallery) ThemeGallery(onDismiss = { gallery = false })
        if (paletteFor(prefs.theme).let { it.light && it.wash }) {
            Options(
                label = "Backdrop",
                help = "The soft colour behind the screens.",
                options = Washes.map { Opt(it.key, it.name) },
                selected = washFor(prefs.wash).key,
            ) { prefs.changeWash(it) }
        }
        Section("Guide")
        Options(
            label = "Programme details",
            help = "Below or above the guide (a band with the programme's picture, so the guide uses the full width), or beside it.",
            options = listOf(Opt(Prefs.BOTTOM, "Below the guide"), Opt(Prefs.TOP, "Above the guide"), Opt(Prefs.SIDE, "Beside the guide")),
            selected = prefs.guideInfo,
        ) { prefs.changeGuideInfo(it) }
        Options(
            label = "Programme actions",
            help = "What OK on a programme opens (in the guide and in search): a panel on the right, ◄ back to the guide; or a card in the middle over the dimmed guide, like the other menus.",
            options = listOf(Opt(Prefs.DOCKED, "Docked on the right"), Opt(Prefs.FLOATING, "Floating")),
            selected = if (prefs.programActions == Prefs.FLOATING) Prefs.FLOATING else Prefs.DOCKED,
        ) { prefs.changeProgramActions(it) }
        Options(
            label = "Background art",
            help = "Rest on a programme and its picture fades in behind the guide, which turns see-through. Off keeps the plain dark guide.",
            options = listOf(Opt(0, "Off")) + (2..5).map { Opt(it, "After $it s") },
            selected = prefs.guideArtSeconds,
        ) { prefs.changeGuideArtSeconds(it) }
        Section("Recordings")
        Options(
            label = "Record series",
            help = "Show the series options first (which episodes, which channel, padding, how many to keep), or set it up straight away with the server's defaults.",
            options = listOf(Opt(true, "Show the options first"), Opt(false, "Set it up straight away")),
            selected = prefs.seriesOptionsFirst,
        ) { prefs.changeSeriesOptionsFirst(it) }
        Options(
            label = "Commercials",
            help = "Found by Comskip, also while a programme is still recording.",
            options = SkipOptions,
            selected = prefs.commercialSkip,
        ) { prefs.changeCommercialSkip(it) }
        Options(
            label = "Intros, recaps and previews",
            help = "Found by Intro Skipper.",
            options = SkipOptions,
            selected = prefs.introSkip,
        ) { prefs.changeIntroSkip(it) }
        Section("Menu")
        Choice(
            label = "Menu entries",
            help = "Their order, which are shown, the Libraries group, where Settings, Sign out and Exit go, and the icons beside them.",
            value = "Arrange…",
            modifier = Modifier.focusRequester(menuButton),
        ) { menuPage = true }
        Section("Home")
        // Home's tiles: a preset, or each one On, Off or (two of them) Auto.
        val preset = prefs.homePreset()
        Options(
            label = "Home layout",
            help = "Calm: the last channel, what records next and new recordings, plus what's recording or needs attention only when there is some. Standard adds the weather and the tuners. Everything shows every tile.",
            options =
                Prefs.HOME_PRESETS.keys.map { k ->
                    Opt(
                        k,
                        when (k) {
                            Prefs.CALM -> "Calm"
                            Prefs.STANDARD -> "Standard"
                            Prefs.EVERYTHING -> "Everything"
                            else -> k
                        },
                    )
                },
            selected = preset,
            otherLabel = "Your own",
        ) { it?.let(prefs::applyHomePreset) }
        listOf(
            Triple("live", "Back to live", false),
            Triple("weather", "Weather tile", false),
            Triple("now", "Recording now", true),
            Triple("next", "Recording next", false),
            Triple("attention", "Needs attention", true),
            Triple("tuners", "Tuners and recordings drive", false),
            Triple("fresh", "New recordings", false),
            Triple("libraries", "Recently added in each library", false),
        ).forEach { (key, label, auto) ->
            val v = prefs.homeTile(key)
            Options(
                label = label,
                help =
                    if (key == "libraries") {
                        "A row per library under Home, newest first, like the web client's home page (▼ from New recordings). Choose which libraries below (a library holding the recordings folder repeats New recordings); ones hidden from the menu are left out."
                    } else if (auto) {
                        "Auto: only while there is something to show. The other tiles grow into the room it leaves."
                    } else {
                        "Turned off, the other tiles grow into the room it leaves."
                    },
                options = if (auto) listOf(Opt(Prefs.ON, "On"), Opt(Prefs.AUTO, "Auto"), Opt(Prefs.OFF, "Off")) else listOf(Opt(Prefs.ON, "On"), Opt(Prefs.OFF, "Off")),
                selected = if (v == Prefs.AUTO || v == Prefs.OFF) v else Prefs.ON,
            ) { prefs.changeHomeTile(key, it) }
            // Under the library rows' switch: which libraries get one.
            if (key == "libraries" && v != Prefs.OFF) {
                libraries.filter { it.id.toString() !in prefs.hiddenLibraries }.inMenuOrder().forEach { lib ->
                    val id = lib.id.toString()
                    Options(
                        label = "   ${lib.name}",
                        help = "Its Recently added row on Home.",
                        options = listOf(Opt(true, "Row"), Opt(false, "No row")),
                        selected = id !in prefs.homeHiddenLibraries,
                    ) { if (it != (id !in prefs.homeHiddenLibraries)) prefs.toggleHomeLibrary(id) }
                }
            }
        }
        Options(
            label = "Weather",
            help = "Today's weather and a sky to match behind Home (from Open-Meteo: only your town is sent).",
            options = listOf(Opt(true, "Shown"), Opt(false, "Off")),
            selected = prefs.weatherOn,
        ) { prefs.changeWeatherOn(it) }
        Options(
            label = "Temperature",
            help = "Degrees Fahrenheit or Celsius.",
            options = listOf(Opt(true, "°F"), Opt(false, "°C")),
            selected = prefs.fahrenheit,
        ) { prefs.changeFahrenheit(it) }
        TownRow()
        Section("Playback")
        Options(
            label = "Subtitles",
            help = "Closed captions or subtitles when something starts, if it has any. The player's Subtitles button switches them any time.",
            options = listOf(Opt(Prefs.ON, "On when available"), Opt(Prefs.OFF, "Off")),
            selected = if (prefs.subtitles == Prefs.ON) Prefs.ON else Prefs.OFF,
        ) { prefs.changeSubtitles(it) }
        Options(
            label = "Info box at the top",
            help = "While a recording, show or movie plays: what it is, when it was recorded and aired, and what's next, at the top whenever the info bar shows. The player's Info button opens the full details either way.",
            options = listOf(Opt(true, "On"), Opt(false, "Off")),
            selected = prefs.infoBoxTop,
        ) { prefs.changeInfoBoxTop(it) }
        Section("Remote")
        Options(
            label = "Hold OK on a poster or episode",
            help = "Off: holding OK is just a press. Quick actions: play, from the start, details, mark watched, and for recordings favourites, archive and delete. The Menu key, where the remote has one, does the same.",
            options = listOf(Opt(Prefs.HOLD_OFF, "Off"), Opt(Prefs.HOLD_MENU, "Quick actions menu"), Opt(Prefs.HOLD_DETAILS, "Details page")),
            selected = if (prefs.holdOk == Prefs.HOLD_MENU || prefs.holdOk == Prefs.HOLD_DETAILS) prefs.holdOk else Prefs.HOLD_OFF,
        ) { prefs.changeHoldOk(it) }
        Section("Server")
        Choice(
            label = "Server",
            help = "The servers signed in to before are remembered: pick one to go back to it without signing in, or add another.",
            value = (if (Jdvr.isNextPvr) "NextPVR – " else "Jellyfin – ") + (Jdvr.servers.firstOrNull { Jdvr.isCurrent(it) }?.name ?: Jdvr.session.serverUrl ?: "?") + " · Switch…",
        ) { onSwitchServer() }
        // The server features are Jellyfin plugins; NextPVR has none of them.
        if (!Jdvr.isNextPvr) {
            Section("Server features")
            ServerFeaturesList()
        }
        Section("About")
        Text(
            "JDVR ${BuildConfig.VERSION_NAME} · " +
                if (Jdvr.isNextPvr) {
                    "signed in with the PIN to NextPVR at ${Jdvr.session.serverUrl ?: "?"}"
                } else {
                    "signed in as ${Jdvr.session.userName ?: "?"} on ${Jdvr.session.serverUrl ?: "?"}"
                },
            color = Jc.Muted,
            fontSize = 15.sp,
        )
    }
}

private val SkipOptions = listOf(Opt(Prefs.ASK, "Ask (show a Skip button)"), Opt(Prefs.AUTO, "Skip automatically"), Opt(Prefs.OFF, "Don't skip"))

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
    // Shown before the value (the menu icons' sample).
    preview: (@Composable () -> Unit)? = null,
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
            preview?.let {
                Row(Modifier.padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { it() }
            }
            Text(value, color = Jc.Focus, fontSize = 16.sp, modifier = Modifier.width(260.dp))
        }
    }
}

/**
 * The weather's town: OK on the box opens the on-screen keyboard to type it, Find (or the
 * keyboard's Done) looks it up and keeps the best match. The box only takes typing after OK, so
 * moving past it doesn't pop the keyboard up (which would then take the arrow keys).
 */
@Composable
private fun TownRow() {
    val prefs = Jdvr.prefs
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    // Typing ends when the box loses focus, but only once it has had it (it starts unfocused).
    var hadFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val field = remember { FocusRequester() }
    val box = remember { FocusRequester() }

    fun find() {
        val q = text.trim()
        if (q.isEmpty()) return
        scope.launch {
            note =
                attempt {
                    com.mackandall.jdvr.data.Weather
                        .find(q)
                }?.let {
                    prefs.changeWeatherPlace(it)
                    text = ""
                    "Weather for ${it.name}"
                } ?: "Couldn't find \"$q\" (check the spelling, or add the state: Springfield, IL)"
        }
    }
    Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Town: " + (prefs.weatherPlace?.name ?: "not set"), color = Jc.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val boxLook =
                Modifier
                    .border(BorderStroke(2.dp, if (editing) Jc.Focus else Jc.Line), RoundedCornerShape(10.dp))
                    .background(Jc.Panel2, RoundedCornerShape(10.dp))
            if (!editing) {
                Surface(
                    onClick = {
                        hadFocus = false
                        editing = true
                    },
                    modifier = Modifier.weight(1f).focusRequester(box),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                    border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(2.dp, Jc.Line)), focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Text(text.ifEmpty { "Town, e.g. Atlanta or Springfield, IL (OK to type)" }, color = if (text.isEmpty()) Jc.Muted else Jc.Text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp))
                }
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle =
                        androidx.compose.ui.text
                            .TextStyle(color = Jc.Text, fontSize = 16.sp),
                    cursorBrush =
                        androidx.compose.ui.graphics
                            .SolidColor(Jc.Focus),
                    keyboardOptions =
                        androidx.compose.foundation.text
                            .KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            find()
                            editing = false
                        }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(field)
                            // Leaving the box (Back, or ▲▼ out of it) ends typing.
                            .onFocusChanged {
                                if (it.isFocused) {
                                    hadFocus = true
                                } else if (hadFocus && editing) {
                                    editing = false
                                }
                            }.onPreviewKeyEvent { e ->
                                val dir =
                                    when (e.key) {
                                        Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                                        Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                                        else -> null
                                    }
                                if (dir != null && e.type == KeyEventType.KeyDown) {
                                    focusManager.moveFocus(dir)
                                    true
                                } else {
                                    false
                                }
                            }.then(boxLook)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("Town, e.g. Atlanta or Springfield, IL", color = Jc.Muted, fontSize = 16.sp)
                        inner()
                    },
                )
                LaunchedEffect(Unit) {
                    field.focusSoon()
                }
            }
            Button("Find", primary = true) { find() }
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
private fun MenuSettings(libraries: List<Library>) {
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
                listOf(
                    MRow(
                        k,
                        com.mackandall.jdvr.Screen.entries
                            .first { s -> s.key == k }
                            .label,
                        false,
                    ),
                )
            }
        }
    var lifted by remember { mutableStateOf<String?>(null) }
    // The order before the pick-up, for Back.
    var before by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { first.tryFocus() }

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
            Text("Settings", color = Jc.Title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
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

                                        Key.DirectionLeft, Key.DirectionRight -> {
                                            true
                                        }

                                        else -> {
                                            false
                                        }
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
        Options(
            label = "Libraries in the menu",
            help = "One Libraries entry that opens and closes (OK or ►; ◄ closes it), or each library on its own line.",
            options = listOf(Opt(true, "Grouped"), Opt(false, "Each on its own line")),
            selected = prefs.librariesGrouped,
        ) { prefs.changeLibrariesGrouped(it) }
        if (prefs.librariesGrouped) {
            Options(
                label = "Libraries group starts",
                help = "How the group is when the menu opens.",
                options = listOf(Opt(Prefs.LAST, "As last left"), Opt(Prefs.CLOSED, "Closed"), Opt(Prefs.OPEN, "Open")),
                selected = if (prefs.librariesStart == Prefs.OPEN || prefs.librariesStart == Prefs.CLOSED) prefs.librariesStart else Prefs.LAST,
            ) { prefs.changeLibrariesStart(it) }
        }
        Options(
            label = "Settings, Sign out and Exit",
            help = "A row of icons under the menu (▼ from the last entry), or the last three entries.",
            options = listOf(Opt(true, "Icons under the menu"), Opt(false, "Last in the list")),
            selected = prefs.menuIcons,
        ) { prefs.changeMenuIcons(it) }
        // Icons beside the entries: a set (ui/MenuIcons.kt), how they move with focus, how big.
        val set = iconSetFor(prefs.menuIconSet)
        Options(
            label = "Menu icons",
            help = "An icon beside each entry. Line: thin outlines. Solid: filled shapes. Tile: outlines on a soft square.",
            options = IconSets.map { Opt(it.key, it.name) } + Opt(Prefs.OFF, "Off"),
            selected = if (set != null) prefs.menuIconSet else Prefs.OFF,
            preview =
                set?.let { st ->
                    {
                        listOf(MenuIcon.Home, MenuIcon.Guide, MenuIcon.LiveTv, MenuIcon.Recorded, MenuIcon.Scheduled).forEach {
                            MenuGlyph(it, st, 22.dp, Jc.Text, Jc.Focus)
                        }
                    }
                },
        ) { prefs.changeMenuIconSet(it) }
        if (set != null) {
            Options(
                label = "Icon animation",
                help = "As you move through the menu. Magnify: the focused entry's icon grows. Wave: it grows most, the ones beside it a little.",
                options = listOf(Opt(Prefs.MAGNIFY, "Magnify"), Opt(Prefs.WAVE, "Wave"), Opt(Prefs.OFF, "Off")),
                selected = if (prefs.menuIconMotion == Prefs.MAGNIFY || prefs.menuIconMotion == Prefs.WAVE) prefs.menuIconMotion else Prefs.OFF,
            ) { prefs.changeMenuIconMotion(it) }
            if (prefs.menuIconMotion != Prefs.OFF) {
                Options(
                    label = "Focused icon size",
                    help = "How big the focused icon grows. It always stays inside its entry, so it is never cut off.",
                    options = listOf(Opt(Prefs.SMALL, "Small"), Opt(Prefs.MEDIUM, "Medium"), Opt(Prefs.LARGE, "Large")),
                    selected = if (prefs.menuIconSize == Prefs.SMALL || prefs.menuIconSize == Prefs.LARGE) prefs.menuIconSize else Prefs.MEDIUM,
                    preview = {
                        val k = menuIconGrowth(prefs.menuIconSize)
                        MenuGlyph(MenuIcon.Home, set, 22.dp, Jc.Muted, Jc.Focus)
                        MenuGlyph(
                            MenuIcon.Home,
                            set,
                            22.dp,
                            Jc.Focus,
                            Jc.Focus,
                            Modifier.graphicsLayer {
                                scaleX = k
                                scaleY = k
                            },
                        )
                    },
                ) { prefs.changeMenuIconSize(it) }
            }
        }
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

/**
 * The server plugins JDVR does more with: ✓ installed, ○ not installed (with where to get it), or
 * "ask an administrator" when this user can't tell.
 */
@Composable
private fun ServerFeaturesList() {
    var state by remember { mutableStateOf<Map<java.util.UUID, Boolean?>?>(null) }
    LaunchedEffect(Unit) {
        state =
            com.mackandall.jdvr.data.ServerFeatures
                .check()
    }
    Text(
        "JDVR works with any Jellyfin server; these optional plugins on the server add more. Add one in Jellyfin's dashboard: Plugins › Repositories (the address below), then Plugins › Catalog.",
        color = Jc.Muted,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth(0.8f),
    )
    val s = state
    if (s == null) {
        Text("Checking the server…", color = Jc.Muted, fontSize = 15.sp)
        return
    }
    com.mackandall.jdvr.data.ServerFeatures.plugins.forEach { p ->
        val has = s[p.id]
        // Focusable, so the list scrolls with the remote like the rest of Settings.
        Surface(
            onClick = {},
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    when (has) {
                        true -> "✓"
                        false -> "○"
                        null -> "?"
                    },
                    color = if (has == true) Jc.Green else Jc.Muted,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.adds, color = Jc.Muted, fontSize = 14.sp)
                    when (has) {
                        true -> {
                            Text("Installed", color = Jc.Green, fontSize = 13.sp)
                        }

                        null -> {
                            Text("Only an administrator's account can tell whether the server has it.", color = Jc.Muted, fontSize = 13.sp)
                        }

                        false -> {
                            Text(
                                p.repository?.let { "Not installed. Repository: $it" } ?: "Not installed. Not published yet.",
                                color = if (p.repository != null) Jc.Focus else Jc.Muted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
