package com.mackandall.jdvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.player.PlayerScreen
import com.mackandall.jdvr.ui.ChannelsScreen
import com.mackandall.jdvr.ui.GuideScreen
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.JdvrTheme
import com.mackandall.jdvr.ui.LoginScreen
import com.mackandall.jdvr.ui.Rail
import java.util.UUID

/** The screens. Kept as plain state: there are few, and the player sits on top of the others. */
sealed interface Screen {
    data object LiveTv : Screen

    data object Guide : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JdvrTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot() {
    var signedIn by remember { mutableStateOf(Jdvr.signedIn) }
    var screen by remember { mutableStateOf<Screen>(Screen.Guide) }
    var playing by remember { mutableStateOf<UUID?>(null) }

    Box(Modifier.fillMaxSize().background(Jc.Ink)) {
        if (!signedIn) {
            LoginScreen(onSignedIn = { signedIn = true })
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            Rail(
                selected = screen,
                onSelect = { screen = it },
                onSignOut = {
                    Jdvr.signOut()
                    signedIn = false
                },
            )
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (screen) {
                    Screen.LiveTv -> ChannelsScreen(onPlay = { playing = it })
                    Screen.Guide -> GuideScreen(onPlay = { playing = it })
                }
            }
        }
        playing?.let { channelId ->
            BackHandler { playing = null }
            PlayerScreen(
                channelId = channelId,
                onExit = { playing = null },
                onChannelChange = { playing = it },
            )
        }
    }
}
