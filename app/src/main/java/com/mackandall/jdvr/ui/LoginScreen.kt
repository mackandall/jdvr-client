package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber

/**
 * Sign in. The server: the last one signed in to, or one found on the network (Jellyfin answers a
 * local broadcast), or typed. Then Quick Connect: the TV shows a code to approve from the web client
 * (Profile > Quick Connect), so nobody types a password with a remote. Username and password as a
 * fallback.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(Jdvr.session.serverUrl ?: "") }
    // Servers that answered on the network: name and address.
    var found by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(0) }
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

    // Look for Jellyfin servers on the network; the first to answer fills an empty address.
    LaunchedEffect(search) {
        searching = true
        try {
            withContext(Dispatchers.IO) {
                Jdvr.jellyfin.discovery.discoverLocalServers().collect { s ->
                    val address = s.address.trimEnd('/')
                    withContext(Dispatchers.Main) {
                        if (found.none { it.second == address }) found = found + ((s.name ?: address) to address)
                        if (server.isBlank()) server = address
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Server discovery failed")
        }
        searching = false
    }

    // Quick Connect: ask for a code, then poll until someone approves it. A code asked for in the
    // last few minutes is kept (the screen can be rebuilt, e.g. when the app is opened again from
    // the home screen), so the one someone is typing in the web client stays the one shown.
    LaunchedEffect(server, attempt, usePassword) {
        if (usePassword || server.isBlank()) return@LaunchedEffect
        code = null
        try {
            val base = server.trimEnd('/')
            val api = Jdvr.jellyfin.createApi(baseUrl = base)
            val kept = PendingCode.takeIf { it?.server == base && it.attempt == attempt && System.currentTimeMillis() - it.at < 9 * 60_000 }
            var state =
                kept?.let { k -> runCatching { withContext(Dispatchers.IO) { api.quickConnectApi.getQuickConnectState(secret = k.secret).content } }.getOrNull() }
                    ?: withContext(Dispatchers.IO) { api.quickConnectApi.initiateQuickConnect().content }.also {
                        PendingCode = PendingQuickConnect(base, attempt, it.secret, System.currentTimeMillis())
                    }
            code = state.code
            message = null
            while (!state.authenticated) {
                delay(3000)
                state = withContext(Dispatchers.IO) { api.quickConnectApi.getQuickConnectState(secret = state.secret).content }
            }
            PendingCode = null
            finish(withContext(Dispatchers.IO) { api.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = state.secret)).content })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Quick Connect failed")
            message = "Can't reach Quick Connect on $server (${e.message}). Check the address, or sign in with a password."
        }
    }

    Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(620.dp).background(Jc.Panel, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Sign in to Jellyfin", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Field("Server", server, onChange = { server = it }, keyboard = KeyboardType.Uri)
            // Found on the network: pick one (shown when there's a choice, or the address was changed).
            val others = found.filter { it.second != server.trimEnd('/') }
            if (others.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    others.take(3).forEach { (name, address) -> Button("Use $name") { server = address } }
                }
            }
            if (server.isBlank()) {
                Text(
                    if (searching) "Looking for Jellyfin servers on your network…" else "No server found on the network. Type its address, e.g. http://192.168.1.10:8096",
                    color = Jc.Muted,
                    fontSize = 16.sp,
                )
                if (!searching) Button("Look again") { search++ }
            } else if (!usePassword) {
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
                                finish(withContext(Dispatchers.IO) { api.userApi.authenticateUserByName(username = user, password = password).content })
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

// The Quick Connect code being shown, kept across a rebuilt screen (see LoginScreen).
private data class PendingQuickConnect(
    val server: String,
    val attempt: Int,
    val secret: String,
    val at: Long,
)

private var PendingCode: PendingQuickConnect? = null

/**
 * A text box that takes typing only after OK (then the on-screen keyboard opens); otherwise it's a
 * plain focusable box, so landing on it or moving past it doesn't pop the keyboard up over the
 * screen. Leaving it (Back, ▲▼) ends typing.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    // Typing ends when the box loses focus, but only once it has had it (it starts unfocused).
    var hadFocus by remember { mutableStateOf(false) }
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    val box = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Jc.Muted, fontSize = 14.sp)
        if (!editing) {
            androidx.tv.material3.Surface(
                onClick = {
                    hadFocus = false
                    editing = true
                },
                modifier = Modifier.fillMaxWidth().focusRequester(box),
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Jc.Panel2, focusedContainerColor = Jc.Panel2, contentColor = Jc.Text, focusedContentColor = Jc.Text),
                border =
                    androidx.tv.material3.ClickableSurfaceDefaults.border(
                        border = androidx.tv.material3.Border(BorderStroke(2.dp, Jc.Line)),
                        focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, Jc.Focus)),
                    ),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    if (secret) "•".repeat(value.length) else value.ifEmpty { " " },
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Jc.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(Jc.Focus),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { editing = false }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true else if (hadFocus && editing) editing = false
                        }
                        .onPreviewKeyEvent { e ->
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
                        }
                        .border(BorderStroke(2.dp, Jc.Focus), RoundedCornerShape(10.dp))
                        .background(Jc.Panel2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            LaunchedEffect(Unit) {
                repeat(20) {
                    if (runCatching { field.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(30)
                }
            }
        }
        // Done on the keyboard puts focus back on the box (only after typing, not when the screen opens).
        var typed by remember { mutableStateOf(false) }
        LaunchedEffect(editing) {
            if (editing) {
                typed = true
            } else if (typed) {
                runCatching { box.requestFocus() }
            }
        }
    }
}
