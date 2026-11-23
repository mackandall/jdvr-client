package com.mackandall.jdvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LibItem
import com.mackandall.jdvr.data.Library
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.player.PlayerScreen
import com.mackandall.jdvr.player.RecordingPlayerScreen
import com.mackandall.jdvr.ui.ChannelsScreen
import com.mackandall.jdvr.ui.GuideScreen
import com.mackandall.jdvr.ui.HomeScreen
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.JdvrTheme
import com.mackandall.jdvr.ui.LibraryScreen
import com.mackandall.jdvr.ui.LoginScreen
import com.mackandall.jdvr.ui.Rail
import com.mackandall.jdvr.ui.RecordedShowsScreen
import com.mackandall.jdvr.ui.ScheduledScreen
import com.mackandall.jdvr.ui.SeriesScreen
import com.mackandall.jdvr.ui.SettingsScreen
import com.mackandall.jdvr.ui.menuHome
import com.mackandall.jdvr.ui.ownHome
import com.mackandall.jdvr.ui.screenBackground
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.delay
import java.util.UUID

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

    // In the menu only while the server has open problems (and from the popup, Home and Settings).
    Problems("Problems", "problems"),
}

/** A recording to play: from where, and (for a live buffer) the channel to go back to. */
data class PlayRecording(
    val itemId: UUID,
    val startMs: Long? = null,
    val liveChannelId: UUID? = null,
    // Paused from live TV: open the buffer paused.
    val paused: Boolean = false,
)

/**
 * Where the app is: a section, and how it opens (the guide at a time or on a channel, Recorded
 * Shows with a view and filter or on a show, a library on an item, Settings on its menu page,
 * Home focusing the poster it was left from). Back returns to the place as it was left.
 */
data class Place(
    val screen: Screen,
    val libraryId: UUID? = null,
    val guideAt: java.time.LocalDateTime? = null,
    val guideChannel: UUID? = null,
    val recordingsPreset: com.mackandall.jdvr.ui.RecordingsPreset? = null,
    // Opened straight away, then cleared (from Home's New recordings and library rows).
    val openShow: String? = null,
    val openLibItem: LibItem? = null,
    val settingsMenuPage: Boolean = false,
    // Home: the poster to focus again on the way back from its library item.
    val homePoster: UUID? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testHooks(intent)
        setContent { JdvrTheme { AppShell() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        testHooks(intent)
    }

    // Debug builds: `--es mvTest http://host:port` starts Multiview against a stand-in server
    // (recorded files, no tuners: see JdvrDecodeTest/server/mv-standin.py).
    private fun testHooks(intent: android.content.Intent?) {
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra("mvTest")?.let {
            com.mackandall.jdvr.player.Multiview.pendingTest = it to intent.getIntExtra("mvCount", 4)
        }
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
    if (signedIn) {
        com.mackandall.jdvr.ui
            .MoveWatcherEffect()
    }
    var place by remember { mutableStateOf(Place(Screen.Home)) }
    val screen = place.screen
    val libraryId = place.libraryId
    // Places reached from inside another (Home's Guide, Scheduled, a show): Back returns there.
    // Picking from the rail starts afresh.
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Place>() }

    fun goTo(p: Place) {
        if (p.screen != screen || p.libraryId != libraryId) backStack.add(place)
        place = p
    }

    fun goBack() {
        if (backStack.isNotEmpty()) place = backStack.removeAt(backStack.lastIndex)
    }

    // From the rail: afresh, with nothing to go back to.
    fun startAt(p: Place) {
        backStack.clear()
        place = p
    }
    // The SageTV themes have no slide-out menu: Home is their menu. Back on it twice leaves the app.
    val themedHome = Jc.Style.menuHome
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
    LaunchedEffect(screen, currentLibrary, libraries) { if (screen == Screen.Library && libraries.isNotEmpty() && currentLibrary == null) place = Place(Screen.Home) }
    var playingChannel by remember { mutableStateOf<UUID?>(null) }
    var playingRecording by remember { mutableStateOf<PlayRecording?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // Live TV: the picture (it outlives the full-screen player: Back can leave it playing in the
    // guide), and the player shown full screen over it.
    fun watchChannel(id: UUID) {
        com.mackandall.jdvr.player.LivePictures.watch(appContext, id)
        playingChannel = id
    }
    // Back from live TV: in the guide (Settings › Guide › Keep playing), the channel plays on in
    // the details picture; anywhere else, or with it off, it stops.
    fun leaveLive() {
        val m = com.mackandall.jdvr.player.LivePictures.main
        val keep = Jdvr.prefs.guideKeepPlaying && screen == Screen.Guide && !railOpen && m != null && m.stream.showing && !m.stream.tuning
        playingChannel = null
        if (keep) {
            // Swapped: the channel watched big plays on in the guide (the one in the corner closes).
            val big = com.mackandall.jdvr.player.LivePictures.extra?.takeIf { com.mackandall.jdvr.player.LivePictures.swapped }
            if (big != null) com.mackandall.jdvr.player.LivePictures.watch(appContext, big.channelId)
            com.mackandall.jdvr.player.LivePictures.closeExtra()
            m.setVolume(com.mackandall.jdvr.player.guideVolume())
        } else {
            com.mackandall.jdvr.player.LivePictures.closeAll()
        }
    }
    val livePicture = com.mackandall.jdvr.player.LivePictures.main
    // Playing in the guide (not full screen).
    val inGuidePicture = playingChannel == null && livePicture != null
    // Leaving the guide (another section, a recording) stops it.
    LaunchedEffect(screen, playingRecording, inGuidePicture) {
        // A recording opening from it (perhaps its own buffer) keeps the tuner's grace.
        if (inGuidePicture && (screen != Screen.Guide || playingRecording != null)) com.mackandall.jdvr.player.LivePictures.closeAll(free = playingRecording == null)
    }
    LaunchedEffect(inGuidePicture, Jdvr.prefs.guideSound) {
        if (inGuidePicture) livePicture?.setVolume(com.mackandall.jdvr.player.guideVolume())
    }
    // Bumped when a recording or video stops, so screens re-read watched marks and resume points.
    var played by remember { mutableIntStateOf(0) }
    LaunchedEffect(playingRecording == null) { if (playingRecording == null) played++ }
    // Where focus goes on the current screen when the rail closes (each screen attaches it).
    val entry = remember(screen, libraryId) { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (!signedIn) {
        Box(Modifier.fillMaxSize().screenBackground()) {
            LoginScreen(onSignedIn = {
                signedIn = true
                onServerChanged()
            })
        }
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
            entry.tryFocus()
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
                    val returned = !screenHasFocus && entry.tryFocus() && screenHasFocus
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
        // The guide with a channel playing in its picture: Back stops it, or (Settings › Guide)
        // goes back to it full screen. Always composed, after the one below, so it comes first.
        val guideBackLive = inGuidePicture && !railOpen && screen == Screen.Guide
        if (inGuidePicture) com.mackandall.jdvr.player.OnLeaveApp { com.mackandall.jdvr.player.LivePictures.closeAll() }
        BackHandler(enabled = !railOpen && playingChannel == null && playingRecording == null) {
            when {
                backStack.isNotEmpty() -> {
                    goBack()
                }

                screen != Screen.Home -> {
                    place = Place(Screen.Home)
                }

                themedHome && exitArmed -> {
                    backActivity?.finish()
                }

                themedHome -> {
                    exitArmed = true
                    android.widget.Toast
                        .makeText(backActivity, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }

                else -> {
                    railOpen = true
                }
            }
        }
        BackHandler(enabled = guideBackLive) {
            val m = com.mackandall.jdvr.player.LivePictures.main
            if (Jdvr.prefs.guideBack == com.mackandall.jdvr.data.Prefs.FULL && m != null) {
                playingChannel = m.channelId
            } else {
                com.mackandall.jdvr.player.LivePictures.closeAll()
            }
        }
        // While a player is up, the screen under it is out of reach: a key the player has no use
        // for (Down at the bottom of its controls) made the focus search step into this screen,
        // and the next OK started whatever was selected there (another recording over the live
        // channel). Focus can't enter it, and any key that still reaches it is dropped.
        val playerUp = playingChannel != null || playingRecording != null
        // Channels JDVR: its icon strip down the left of every screen (Home draws its own).
        val channelsStrip = Jc.Style == com.mackandall.jdvr.ui.UiStyle.Channels && screen != Screen.Home
        if (channelsStrip) {
            com.mackandall.jdvr.ui.ChannelsSideStrip(
                selected = screen,
                libraryId = libraryId,
                libraries = shownLibraries,
                showRecorded = hasDvr,
                onSelect = { startAt(Place(it)) },
                onSelectLibrary = { startAt(Place(Screen.Library, libraryId = it)) },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = if (channelsStrip) 58.dp else 0.dp)
                .focusProperties { onEnter = { if (playerUp) cancelFocusChange() } }
                .focusGroup()
                .onPreviewKeyEvent { playerUp }
                .onFocusChanged { screenHasFocus = it.hasFocus },
        ) {
            androidx.compose.runtime.CompositionLocalProvider(com.mackandall.jdvr.ui.LocalWatchLive provides { id: UUID -> watchChannel(id) }) {
                when (screen) {
                    Screen.Home -> {
                        // Both SageTV themes: Home is SageTV's menu (Gemstone draws it in its own look).
                        if (themedHome) {
                            com.mackandall.jdvr.ui.SageHome(
                                entry = entry,
                                libraries = shownLibraries,
                                showRecorded = hasDvr,
                                showTrash = hasTrash,
                                onOpenScreen = { goTo(Place(it)) },
                                onOpenGuide = { at, ch -> goTo(Place(Screen.Guide, guideAt = at, guideChannel = ch)) },
                                onOpenRecordings = { goTo(Place(Screen.RecordedShows, recordingsPreset = it)) },
                                onOpenLibrary = { goTo(Place(Screen.Library, libraryId = it)) },
                                onOpenMenuSettings = { goTo(Place(Screen.Settings, settingsMenuPage = true)) },
                                onPlayChannel = { watchChannel(it) },
                                onSignOut = {
                                    Jdvr.signOut()
                                    signedIn = false
                                },
                                onSwitchServer = onSwitchServer,
                                onExit = { backActivity?.finish() },
                            )
                        } else if (Jc.Style.ownHome) {
                            // Embuary, Channels and Estuary look-alikes: Home laid out as theirs.
                            com.mackandall.jdvr.ui.LookalikeHome(
                                entry = entry,
                                libraries = shownLibraries,
                                showRecorded = hasDvr,
                                onPlayRecording = { playingRecording = it },
                                onOpenScreen = { goTo(Place(it)) },
                                onOpenShow = { goTo(Place(Screen.RecordedShows, openShow = it)) },
                                onOpenLibrary = { goTo(Place(Screen.Library, libraryId = it)) },
                            )
                        } else {
                            HomeScreen(
                                entry = entry,
                                onPlayChannel = { watchChannel(it) },
                                onPlayRecording = { playingRecording = it },
                                onOpenScreen = { goTo(Place(it)) },
                                onOpenShow = { goTo(Place(Screen.RecordedShows, openShow = it)) },
                                onOpenLibraryItem = { lib, item ->
                                    place = place.copy(homePoster = item.id)
                                    goTo(Place(Screen.Library, libraryId = lib, openLibItem = item))
                                },
                                returnPoster = place.homePoster,
                                onReturned = { place = place.copy(homePoster = null) },
                            )
                        }
                    }

                    Screen.Guide -> {
                        GuideScreen(entry = entry, onPlay = { watchChannel(it) }, onPlayRecording = { playingRecording = it }, watching = playingChannel ?: livePicture?.channelId, livePicture = livePicture.takeIf { inGuidePicture }, onWatchHere = { livePicture?.changeChannel(it) }, startAt = place.guideAt, startChannel = place.guideChannel)
                    }

                    Screen.LiveTv -> {
                        ChannelsScreen(entry = entry, onPlay = { watchChannel(it) })
                    }

                    Screen.RecordedShows -> {
                        RecordedShowsScreen(entry = entry, onPlay = { playingRecording = PlayRecording(it) }, openShowId = place.openShow, onShowOpened = { place = place.copy(openShow = null) }, onLeave = ::goBack, refresh = played, onPlayItem = { playingRecording = it }, preset = place.recordingsPreset)
                    }

                    Screen.Library -> {
                        currentLibrary?.let { lib ->
                            androidx.compose.runtime.key(lib.id) { LibraryScreen(library = lib, entry = entry, refresh = played, onPlay = { playingRecording = it }, openItem = place.openLibItem, onItemOpened = { place = place.copy(openLibItem = null) }, onLeave = ::goBack) }
                        }
                    }

                    Screen.Search -> {
                        com.mackandall.jdvr.ui
                            .SearchScreen(entry = entry, refresh = played, onPlayChannel = { watchChannel(it) }, onPlay = { playingRecording = it })
                    }

                    Screen.Scheduled -> {
                        ScheduledScreen(entry = entry, onPlayRecording = { playingRecording = it })
                    }

                    Screen.Series -> {
                        SeriesScreen(entry = entry, onOpenShow = { goTo(Place(Screen.RecordedShows, openShow = it)) })
                    }

                    Screen.Trash -> {
                        com.mackandall.jdvr.ui
                            .TrashScreen(entry = entry)
                    }

                    Screen.Settings -> {
                        SettingsScreen(
                            entry = entry,
                            libraries = libraries,
                            openMenuPage = place.settingsMenuPage,
                            onSwitchServer = onSwitchServer,
                            onOpenProblems = { goTo(Place(Screen.Problems)) },
                        )
                    }

                    Screen.Problems -> {
                        com.mackandall.jdvr.ui
                            .ProblemsScreen(entry = entry, onPlayRecording = { playingRecording = it })
                    }
                }
            }
        }
        // Quiet problems (Settings › Problems): a short amber mark on the left edge, where the menu
        // opens, while any are open. It takes no focus; ◄ opens the menu with Problems in it.
        if (!railOpen && !playerUp && screen != Screen.Problems &&
            com.mackandall.jdvr.ui.OpenProblems.count > 0 &&
            com.mackandall.jdvr.ui.OpenProblems.mode == com.mackandall.jdvr.data.Prefs.PROBLEMS_QUIET
        ) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.CenterStart,
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .width(6.dp)
                        .height(64.dp)
                        .background(
                            com.mackandall.jdvr.ui.Jc.Amber,
                            androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp),
                        ),
                )
            }
        }
        // New problems on the server (a failed recording, a stuck tuner): a popup when the app opens.
        com.mackandall.jdvr.ui.ProblemsPopup(
            resumed = librariesLoad,
            playing = playerUp,
            onSeeProblems = {
                railOpen = false
                goTo(Place(Screen.Problems))
            },
        )
        if (railOpen) {
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            BackHandler {
                railOpen = false
                if (screen != Screen.Home) {
                    startAt(Place(Screen.Home))
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
                    startAt(Place(it))
                    railOpen = false
                },
                libraries = shownLibraries,
                showRecorded = hasDvr,
                showTrash = hasTrash,
                problems =
                    com.mackandall.jdvr.ui.OpenProblems.count.takeIf {
                        com.mackandall.jdvr.ui.OpenProblems.mode != com.mackandall.jdvr.data.Prefs.PROBLEMS_SILENT
                    } ?: 0,
                selectedLibrary = libraryId,
                onSelectLibrary = {
                    startAt(Place(Screen.Library, libraryId = it.id))
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
            BackHandler { leaveLive() }
            val picture = com.mackandall.jdvr.player.LivePictures.main ?: com.mackandall.jdvr.player.LivePictures.watch(appContext, channelId)
            PlayerScreen(
                picture = picture,
                onExit = {
                    playingChannel = null
                    com.mackandall.jdvr.player.LivePictures.closeAll()
                },
                onChannelChange = { watchChannel(it) },
                onMultiview = { first, second ->
                    playingChannel = null
                    // Its tuner's grace kept: the first picture opens the same channel at once.
                    com.mackandall.jdvr.player.LivePictures.closeAll(free = false)
                    com.mackandall.jdvr.player.Multiview.start(appContext, first, second)
                },
                // Rewind into the buffer, or watch the recording from the start.
                onPlayRecording = {
                    playingChannel = null
                    // It plays the buffer (or the recording) next: keep it going.
                    com.mackandall.jdvr.player.LivePictures.closeAll(free = false)
                    playingRecording = it
                },
            )
        }
        // Debug: Multiview against the stand-in server (cleared only once started: cleared first,
        // the effect left composition and was cancelled before the channels came).
        val mvTest = com.mackandall.jdvr.player.Multiview.pendingTest
        LaunchedEffect(mvTest) {
            val (base, count) = mvTest ?: return@LaunchedEffect
            val all = attempt { Jdvr.backend.channels() }.orEmpty()
            if (all.isNotEmpty()) {
                playingChannel = null
                com.mackandall.jdvr.player.LivePictures.closeAll()
                com.mackandall.jdvr.player.Multiview.startTest(appContext, base, all.take(count.coerceIn(1, 4)))
            }
            com.mackandall.jdvr.player.Multiview.pendingTest = null
        }
        if (com.mackandall.jdvr.player.Multiview.active) {
            com.mackandall.jdvr.player.MultiviewScreen(
                onFullScreen = { watchChannel(it) },
                onExit = { com.mackandall.jdvr.player.Multiview.close() },
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
                        watchChannel(channelId)
                    },
                )
            }
        }
    }
}
