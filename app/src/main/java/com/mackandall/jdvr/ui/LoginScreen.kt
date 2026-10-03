package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber

/**
 * Sign in. Quick Connect first: the TV shows a code to approve from the web client (Profile >
 * Quick Connect), so nobody types a password with a remote. Username and password as a fallback.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(Jdvr.session.serverUrl ?: Jdvr.DEFAULT_SERVER) }
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var usePassword by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun finish(result: AuthenticationResult) {
        val u = result.user ?: error("No user in the reply")
        Jdvr.signIn(server.trimEnd('/'), u.id, u.name, result.accessToken ?: error("No access token"))
        onSignedIn()
    }

    // Quick Connect: ask for a code, then poll until someone approves it.
    LaunchedEffect(server, attempt, usePassword) {
        if (usePassword) return@LaunchedEffect
        code = null
        try {
            val api = Jdvr.jellyfin.createApi(baseUrl = server.trimEnd('/'))
            var state = api.quickConnectApi.initiateQuickConnect().content
            code = state.code
            message = null
            while (!state.authenticated) {
                delay(3000)
                state = api.quickConnectApi.getQuickConnectState(secret = state.secret).content
            }
            finish(api.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = state.secret)).content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Quick Connect failed")
            message = "Can't reach Quick Connect on $server (${e.message}). Check the address, or sign in with a password."
        }
    }

    Box(Modifier.fillMaxSize().background(Jc.Ink), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(620.dp).background(Jc.Panel, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Sign in to Jellyfin", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Field("Server", server, onChange = { server = it }, keyboard = KeyboardType.Uri)
            if (!usePassword) {
                if (code != null) {
                    Text("Approve this code from the Jellyfin web client: your profile picture > Quick Connect.", color = Jc.Muted, fontSize = 16.sp)
                    Text(code!!.chunked(3).joinToString(" "), color = Jc.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    Text("Waiting for approval…", color = Jc.Muted, fontSize = 15.sp)
                } else if (message == null) {
                    Text("Getting a Quick Connect code…", color = Jc.Muted, fontSize = 16.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("New code") { attempt++ }
                    Button("Use a password instead") { usePassword = true }
                }
            } else {
                Field("User", user, onChange = { user = it })
                Field("Password", password, onChange = { password = it }, keyboard = KeyboardType.Password, secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("Sign in", primary = true) {
                        scope.launch {
                            try {
                                val api = Jdvr.jellyfin.createApi(baseUrl = server.trimEnd('/'))
                                finish(api.userApi.authenticateUserByName(username = user, password = password).content)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Sign-in failed: ${e.message}"
                            }
                        }
                    }
                    Button("Use Quick Connect") { usePassword = false }
                }
            }
            message?.let { Text(it, color = Jc.Amber, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Jc.Muted, fontSize = 14.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Jc.Text, fontSize = 18.sp),
            cursorBrush = SolidColor(Jc.Focus),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier =
                Modifier
                    .border(BorderStroke(2.dp, if (focused) Jc.Focus else Jc.Line), RoundedCornerShape(10.dp))
                    .background(Jc.Panel2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .onFocusChangedCompat { focused = it },
        )
    }
}
