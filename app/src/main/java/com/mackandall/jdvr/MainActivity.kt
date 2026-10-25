package com.mackandall.jdvr

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.player.PlayerScreen
import com.mackandall.jdvr.player.RecordingPlayerScreen
import com.mackandall.jdvr.ui.ChannelsScreen
import com.mackandall.jdvr.ui.GuideScreen
import com.mackandall.jdvr.ui.screenBackground
import com.mackandall.jdvr.ui.HomeScreen
import com.mackandall.jdvr.ui.LibraryScreen
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.JdvrTheme
import com.mackandall.jdvr.ui.LoginScreen
import com.mackandall.jdvr.ui.Rail
import com.mackandall.jdvr.ui.RecordedShowsScreen
import com.mackandall.jdvr.ui.ScheduledScreen
import com.mackandall.jdvr.ui.SeriesScreen
import com.mackandall.jdvr.ui.SettingsScreen
import kotlinx.coroutines.delay
import java.util.UUID
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.attempt

/** The sections in the left rail. */
enum class Screen(
    val label: String,
    // Its place in Prefs.menuOrder.
    val key: String,
) {
    Search("Search", "search"),
    Home("Home", "home"),
    Guide("Guide", "guide"),
    LiveTv("Live TV", "livetv"),
    RecordedShows("Recorded Shows", "recorded"),
    Library("Library", "libraries"),
    Scheduled("Scheduled", "scheduled"),
    Series("Series", "series"),
    Trash("Trash", "trash"),
    Settings("Settings", "settings"),
}

/** A recording to play: from where, and (for a live buffer) the channel to go back to. */
data class PlayRecording(
    val itemId: UUID,
    val startMs: Long? = null,
    val liveChannelId: UUID? = null,
    // Paused from live TV: open the buffer paused.
    val paused: Boolean = false,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JdvrTheme { AppShell() } }
    }
}

/**
 * The app for the server signed in to, started afresh when another is picked (Switch server: the
 * sign-in screen, with the remembered servers; Back returns to this one).
 */
@Composable
fun AppShell() {
    var serverKey by remember { mutableStateOf(Jdvr.serverKey) }
    var switching by remember { mutableStateOf(false) }
    if (switching) {
        Box(Modifier.fillMaxSize().screenBackground()) {
            LoginScreen(
                onSignedIn = {
                    serverKey = Jdvr.serverKey
                    switching = false
                },
                onCancel = { switching = false },
            )
        }
        return
    }
    androidx.compose.runtime.key(serverKey) { AppRoot(onSwitchServer = { switching = true }, onServerChanged = { serverKey = Jdvr.serverKey }) }
}

/**
 * The rail opens over the screen and closes once a section is picked; Back on a screen, or Left at
 * its left edge, opens it again. Players sit on top of everything.
 */
@Composable
fun AppRoot(
    onSwitchServer: () -> Unit = {},
    // Signed in again after signing out, maybe to another server: everything starts afresh.
    onServerChanged: () -> Unit = {},
) {
    var signedIn by remember { mutableStateOf(Jdvr.signedIn) }
    // Recordings being moved to the archive, for the cards' and details page's animation.
    if (signedIn) com.mackandall.jdvr.ui.MoveWatcherEffect()
    var screen by remember { mutableStateOf(Screen.Home) }
    // A show for Recorded Shows to open straight away (from Home's New recordings).
    var openShow by remember { mutableStateOf<String?>(null) }
    // A library item for its library to open straight away (from Home's library rows).
    var openLibItem by remember { mutableStateOf<LibItem?>(null) }
    // ...and that poster, for Home to focus again on the way back.
    var homePoster by remember { mutableStateOf<UUID?>(null) }
    // Screens reached from inside another (Home's Guide, Scheduled, a show): Back returns there.
    // Picking from the rail starts afresh.
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Screen>() }
    fun goTo(s: Screen) {
        if (s != screen) backStack.add(screen)
        screen = s
    }
    // How the next screen opens, from SageTV's submenus: the guide at a time or on a channel,
    // Recorded Shows with a view and filter, Settings on its menu page. Cleared back on Home.
    var guideAt by remember { mutableStateOf<java.time.LocalDateTime?>(null) }
    var guideChannel by remember { mutableStateOf<UUID?>(null) }
    var recordingsPreset by remember { mutableStateOf<com.mackandall.jdvr.ui.RecordingsPreset?>(null) }
    var settingsMenuPage by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            guideAt = null
            guideChannel = null
            recordingsPreset = null
            settingsMenuPage = false
        }
    }
    // The SageTV themes have no slide-out menu: Home is their menu. Back on it twice leaves the app.
    val themedHome = Jc.Style != com.mackandall.jdvr.ui.UiStyle.Standard
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(3000)
            exitArmed = false
        }
    }
    var railOpen by remember { mutableStateOf(false) }
    // The rail uses Right itself just now (Settings icon → Sign out, or opening Libraries).
    var railKeepsRight by remember { mutableStateOf(false) }
    // The server's video libraries, each its own menu entry (minus the ones hidden in Settings);
    // re-read whenever the app comes back to the front, so new libraries appear by themselves.
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var libraryId by remember { mutableStateOf<UUID?>(null) }
    var librariesLoad by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) librariesLoad++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    // Recorded Shows is offered only on a server with Live TV or recordings (asked with the libraries).
    var hasDvr by remember { mutableStateOf(true) }
    // Trash only where Episode History keeps one (or still holds something from when it did).
    var hasTrash by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn, librariesLoad) {
        if (signedIn) attempt { Jdvr.backend.libraries() }?.let { libraries = it }
        if (signedIn) hasDvr = Jdvr.backend.hasDvr()
        if (signedIn) hasTrash = attempt { Jdvr.backend.trash() }?.let { it.enabled || it.items.isNotEmpty() } == true
    }
    val shownLibraries = libraries.filter { it.id.toString() !in Jdvr.prefs.hiddenLibraries }
    val currentLibrary = shownLibraries.firstOrNull { it.id == libraryId }
    // A library hidden or removed while open: back to Home.
    LaunchedEffect(screen, currentLibrary, libraries) { if (screen == Screen.Library && libraries.isNotEmpty() && currentLibrary == null) screen = Screen.Home }
    var playingChannel by remember { mutableStateOf<UUID?>(null) }
    var playingRecording by remember { mutableStateOf<PlayRecording?>(null) }
    // Bumped when a recording or video stops, so screens re-read watched marks and resume points.
    var played by remember { mutableIntStateOf(0) }
    LaunchedEffect(playingRecording == null) { if (playingRecording == null) played++ }
    // Where focus goes on the current screen when the rail closes (each screen attaches it).
    val entry = remember(screen, libraryId) { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (!signedIn) {
        Box(Modifier.fillMaxSize().screenBackground()) { LoginScreen(onSignedIn = {
            signedIn = true
            onServerChanged()
        }) }
        return
    }

    // Hand focus to the screen once the rail is closed. Its items may still be loading, and a
    // request before they exist does nothing, so keep asking until focus is really in the screen.
    // Back from a player, focus has usually fallen onto whatever was under the player's focused
    // control already (so the screen "has focus"), but not where the user was: ask for the entry anyway.
    var leftPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(screen, libraryId, railOpen, playingChannel, playingRecording) {
        if (playingChannel != null || playingRecording != null) leftPlayer = true
        if (railOpen || playingChannel != null || playingRecording != null) return@LaunchedEffect
        val fromPlayer = leftPlayer
        leftPlayer = false
        repeat(150) { attempt ->
            if (screenHasFocus && !(fromPlayer && attempt == 0)) return@LaunchedEffect
            runCatching { entry.requestFocus() }
            delay(100)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .onPreviewKeyEvent { e ->
                // Right with the rail open: into the screen, and the rail goes away (whether or not
                // focus was in the rail). From the rail itself, back to where the user was (the
                // screen's entry): a plain move right lands on whatever is level with the menu item,
                // in the guide channels away from the one left.
                if (railOpen && !railKeepsRight && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    val returned = !screenHasFocus && runCatching { entry.requestFocus() }.isSuccess && screenHasFocus
                    if (!returned) focusManager.moveFocus(FocusDirection.Right)
                    railOpen = false
                    true
                } else {
                    false
                }
            }.onKeyEvent { e ->
                // Left that nothing on the screen used: at the left edge (in the guide: at "now",
                // nothing earlier to scroll to), so open the rail.
                if (!railOpen && !themedHome && playingChannel == null && playingRecording == null &&
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft
                ) {
                    if (!focusManager.moveFocus(FocusDirection.Left)) railOpen = true
                    true
                } else {
                    false
                }
            },
    ) {
        // Registered before the screens so their own Back (close a panel, leave a show) wins.
        // Always composed, only enabled/disabled: dropping it while a player is up and adding it back
        // afterwards made it the newest handler, so it beat a screen's own (leave a show's episodes).
        // Back, as in Wholphin: from a section to where it was opened from (else Home), from Home
        // to the menu, and from the menu on Home out of the app.
        val backActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        BackHandler(enabled = !railOpen && playingChannel == null && playingRecording == null) {
            when {
                backStack.isNotEmpty() -> screen = backStack.removeAt(backStack.lastIndex)
                screen != Screen.Home -> screen = Screen.Home
                themedHome && exitArmed -> backActivity?.finish()
                themedHome -> {
                    exitArmed = true
                    android.widget.Toast.makeText(backActivity, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> railOpen = true
            }
        }
        // While a player is up, the screen under it is out of reach: a key the player has no use
        // for (Down at the bottom of its controls) made the focus search step into this screen,
        // and the next OK started whatever was selected there (another recording over the live
        // channel). Focus can't enter it, and any key that still reaches it is dropped.
        val playerUp = playingChannel != null || playingRecording != null
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { onEnter = { if (playerUp) cancelFocusChange() } }
                .focusGroup()
                .onPreviewKeyEvent { playerUp }
                .onFocusChanged { screenHasFocus = it.hasFocus },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(com.mackandall.jdvr.ui.LocalWatchLive provides { id: UUID -> playingChannel = id }) {
            when (screen) {
                Screen.Home ->
                    // Both SageTV themes: Home is SageTV's menu (Gemstone draws it in its own look).
                    if (themedHome) {
                        com.mackandall.jdvr.ui.SageHome(
                            entry = entry,
                            libraries = shownLibraries,
                            showRecorded = hasDvr,
                            showTrash = hasTrash,
                            onOpenScreen = { goTo(it) },
                            onOpenGuide = { at, ch ->
                                guideAt = at
                                guideChannel = ch
                                goTo(Screen.Guide)
                            },
                            onOpenRecordings = {
                                recordingsPreset = it
                                goTo(Screen.RecordedShows)
                            },
                            onOpenLibrary = {
                                libraryId = it
                                goTo(Screen.Library)
                            },
                            onOpenMenuSettings = {
                                settingsMenuPage = true
                                goTo(Screen.Settings)
                            },
                            onPlayChannel = { playingChannel = it },
                            onSignOut = {
                                Jdvr.signOut()
                                signedIn = false
                            },
                            onSwitchServer = onSwitchServer,
                            onExit = { backActivity?.finish() },
                        )
                    } else HomeScreen(
                        entry = entry,
                        onPlayChannel = { playingChannel = it },
                        onPlayRecording = { playingRecording = it },
                        onOpenScreen = { goTo(it) },
                        onOpenShow = {
                            openShow = it
                            goTo(Screen.RecordedShows)
                        },
                        onOpenLibraryItem = { lib, item ->
                            libraryId = lib
                            openLibItem = item
                            homePoster = item.id
                            goTo(Screen.Library)
                        },
                        returnPoster = homePoster,
                        onReturned = { homePoster = null },
                    )
                Screen.Guide -> GuideScreen(entry = entry, onPlay = { playingChannel = it }, onPlayRecording = { playingRecording = it }, watching = playingChannel, startAt = guideAt, startChannel = guideChannel)
                Screen.LiveTv -> ChannelsScreen(entry = entry, onPlay = { playingChannel = it })
                Screen.RecordedShows -> RecordedShowsScreen(entry = entry, onPlay = { playingRecording = PlayRecording(it) }, openShowId = openShow, onShowOpened = { openShow = null }, onLeave = { if (backStack.isNotEmpty()) screen = backStack.removeAt(backStack.lastIndex) }, refresh = played, onPlayItem = { playingRecording = it }, preset = recordingsPreset)
                Screen.Library ->
                    currentLibrary?.let { lib ->
                        androidx.compose.runtime.key(lib.id) { LibraryScreen(library = lib, entry = entry, refresh = played, onPlay = { playingRecording = it }, openItem = openLibItem, onItemOpened = { openLibItem = null }, onLeave = { if (backStack.isNotEmpty()) screen = backStack.removeAt(backStack.lastIndex) }) }
                    }
                Screen.Search -> com.mackandall.jdvr.ui.SearchScreen(entry = entry, refresh = played, onPlayChannel = { playingChannel = it }, onPlay = { playingRecording = it })
                Screen.Scheduled -> ScheduledScreen(entry = entry, onPlayRecording = { playingRecording = it })
                Screen.Series -> SeriesScreen(entry = entry)
                Screen.Trash -> com.mackandall.jdvr.ui.TrashScreen(entry = entry)
                Screen.Settings -> SettingsScreen(entry = entry, libraries = libraries, openMenuPage = settingsMenuPage, onSwitchServer = onSwitchServer)
            }
            }
        }
        if (railOpen) {
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            BackHandler {
                railOpen = false
                if (screen != Screen.Home) {
                    backStack.clear()
                    screen = Screen.Home
                } else {
                    activity?.finish()
                }
            }
            Rail(
                // Focus moved off the rail (Right into the screen): it's done, put it away.
                onDismiss = { railOpen = false },
                onKeepsRight = { railKeepsRight = it },
                selected = screen,
                onSelect = {
                    backStack.clear()
                    homePoster = null
                    screen = it
                    railOpen = false
                },
                libraries = shownLibraries,
                showRecorded = hasDvr,
                showTrash = hasTrash,
                selectedLibrary = libraryId,
                onSelectLibrary = {
                    backStack.clear()
                    homePoster = null
                    libraryId = it.id
                    screen = Screen.Library
                    railOpen = false
                },
                onExit = { activity?.finish() },
                onSignOut = {
                    Jdvr.signOut()
                    railOpen = false
                    signedIn = false
                },
                onSwitchServer = {
                    railOpen = false
                    onSwitchServer()
                },
            )
        }
        playingChannel?.let { channelId ->
            BackHandler { playingChannel = null }
            PlayerScreen(
                channelId = channelId,
                onExit = { playingChannel = null },
                onChannelChange = { playingChannel = it },
                // Rewind into the buffer, or watch the recording from the start.
                onPlayRecording = {
                    playingChannel = null
                    playingRecording = it
                },
            )
        }
        playingRecording?.let { rec ->
            BackHandler { playingRecording = null }
            // A new recording (next episode, another buffer) starts a fresh player.
            androidx.compose.runtime.key(rec) {
            RecordingPlayerScreen(
                recording = rec,
                onExit = { playingRecording = null },
                onPlayRecording = { playingRecording = it },
                onBackToLive = { channelId ->
                    playingRecording = null
                    playingChannel = channelId
                },
            )
            }
        }
    }
}
