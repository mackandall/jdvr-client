package com.mackandall.jdvr

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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.player.PlayerScreen
import com.mackandall.jdvr.player.RecordingPlayerScreen
import com.mackandall.jdvr.ui.ChannelsScreen
import com.mackandall.jdvr.ui.GuideScreen
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.JdvrTheme
import com.mackandall.jdvr.ui.LoginScreen
import com.mackandall.jdvr.ui.Rail
import com.mackandall.jdvr.ui.RecordedShowsScreen
import com.mackandall.jdvr.ui.ScheduledScreen
import com.mackandall.jdvr.ui.SeriesScreen
import kotlinx.coroutines.delay
import java.util.UUID

/** The sections in the left rail. */
enum class Screen(
    val label: String,
) {
    Guide("Guide"),
    LiveTv("Live TV"),
    RecordedShows("Recorded Shows"),
    Scheduled("Scheduled"),
    Series("Series"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JdvrTheme { AppRoot() } }
    }
}

/**
 * The rail opens over the screen and closes once a section is picked; Back on a screen, or Left at
 * its left edge, opens it again. Players sit on top of everything.
 */
@Composable
fun AppRoot() {
    var signedIn by remember { mutableStateOf(Jdvr.signedIn) }
    var screen by remember { mutableStateOf(Screen.Guide) }
    var railOpen by remember { mutableStateOf(false) }
    var playingChannel by remember { mutableStateOf<UUID?>(null) }
    var playingRecording by remember { mutableStateOf<UUID?>(null) }
    // Where focus goes on the current screen when the rail closes (each screen attaches it).
    val entry = remember(screen) { FocusRequester() }
    var screenHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (!signedIn) {
        Box(Modifier.fillMaxSize().background(Jc.Ink)) { LoginScreen(onSignedIn = { signedIn = true }) }
        return
    }

    // Hand focus to the screen once the rail is closed. Its items may still be loading, and a
    // request before they exist does nothing, so keep asking until focus is really in the screen.
    LaunchedEffect(screen, railOpen, playingChannel, playingRecording) {
        if (railOpen || playingChannel != null || playingRecording != null) return@LaunchedEffect
        repeat(150) {
            if (screenHasFocus) return@LaunchedEffect
            runCatching { entry.requestFocus() }
            delay(100)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Jc.Ink)
            .onKeyEvent { e ->
                // Left that nothing on the screen used: at the left edge, so open the rail.
                if (!railOpen && playingChannel == null && playingRecording == null &&
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
        if (!railOpen && playingChannel == null && playingRecording == null) {
            BackHandler { railOpen = true }
        }
        Box(Modifier.fillMaxSize().onFocusChanged { screenHasFocus = it.hasFocus }) {
            when (screen) {
                Screen.Guide -> GuideScreen(entry = entry, onPlay = { playingChannel = it })
                Screen.LiveTv -> ChannelsScreen(entry = entry, onPlay = { playingChannel = it })
                Screen.RecordedShows -> RecordedShowsScreen(entry = entry, onPlay = { playingRecording = it })
                Screen.Scheduled -> ScheduledScreen(entry = entry)
                Screen.Series -> SeriesScreen(entry = entry)
            }
        }
        if (railOpen) {
            BackHandler { railOpen = false }
            Rail(
                selected = screen,
                onSelect = {
                    screen = it
                    railOpen = false
                },
                onSignOut = {
                    Jdvr.signOut()
                    railOpen = false
                    signedIn = false
                },
            )
        }
        playingChannel?.let { channelId ->
            BackHandler { playingChannel = null }
            PlayerScreen(
                channelId = channelId,
                onExit = { playingChannel = null },
                onChannelChange = { playingChannel = it },
            )
        }
        playingRecording?.let { itemId ->
            BackHandler { playingRecording = null }
            RecordingPlayerScreen(itemId = itemId, onExit = { playingRecording = null })
        }
    }
}
