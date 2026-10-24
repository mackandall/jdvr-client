package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
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
import com.mackandall.jdvr.data.NextPvrBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber

/**
 * Sign in. The server: the last one signed in to, or one found on the network (Jellyfin answers a
 * local broadcast; NextPVR is found by a sweep of the home network), or typed: a bare address will
 * do, http:// and the usual port (8096 Jellyfin, 8866 NextPVR) are tried. Jellyfin then signs in by
 * Quick Connect: the TV shows a code to approve from the web client (Profile > Quick Connect), so
 * nobody types a password with a remote; username and password as a fallback. NextPVR takes its PIN.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    var server by remember { mutableStateOf(Jdvr.session.serverUrl ?: "") }
    // Servers that answered on the network.
    var found by remember { mutableStateOf<List<FoundServer>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var usePassword by remember { mutableStateOf(false) }
    // The address that answered (what was typed, with http:// and a port added as needed), and who
    // answers there: its name, so a password goes only where the user means it to.
    var base by remember { mutableStateOf<String?>(null) }
    var serverName by remember { mutableStateOf<String?>(null) }
    var serverChecked by remember { mutableStateOf(false) }
    // A NextPVR server at the address (it signs in with its PIN instead).
    var nextPvr by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var armedInsecure by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // Where a sign-in would go: the address that answered, or what was typed.
    val target = base ?: serverCandidates(server).firstOrNull() ?: ""

    fun finish(result: AuthenticationResult) {
        val u = result.user ?: error("No user in the reply")
        Jdvr.signIn(target, u.id, u.name, result.accessToken ?: error("No access token"))
        onSignedIn()
    }

    // Look for servers on the network, Jellyfin and NextPVR at once. With an empty address and
    // just one found, it's filled in; with more, they're listed to pick from.
    LaunchedEffect(search) {
        searching = true
        coroutineScope {
            launch {
                try {
                    withContext(Dispatchers.IO) {
                        Jdvr.jellyfin.discovery.discoverLocalServers().collect { s ->
                            val address = s.address.trimEnd('/')
                            withContext(Dispatchers.Main) {
                                if (found.none { it.address == address }) found = found + FoundServer("Jellyfin", s.name ?: address, address)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Jellyfin discovery failed")
                }
            }
            launch {
                val servers = runCatching { NextPvrBackend.findOnNetwork() }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
                servers.forEach { address ->
                    if (found.none { it.address == address }) found = found + FoundServer("NextPVR", address.removePrefix("http://").substringBefore(':'), address)
                }
            }
        }
        if (server.isBlank() && found.size == 1) server = found.single().address
        searching = false
    }

    // Who answers at the address: Jellyfin as typed, or on 8096; then NextPVR as typed, or on 8866.
    LaunchedEffect(server) {
        base = null
        serverName = null
        serverChecked = false
        nextPvr = false
        armedInsecure = false
        val candidates = serverCandidates(server)
        if (candidates.isEmpty()) return@LaunchedEffect
        delay(400)
        for (c in candidates.filter { !it.endsWith(":8866") }) {
            serverName =
                runCatching {
                    val api = Jdvr.jellyfin.createApi(baseUrl = c)
                    withContext(Dispatchers.IO) { api.systemApi.getPublicSystemInfo().content.serverName }
                }.getOrNull()
            if (serverName != null) {
                base = c
                break
            }
        }
        if (serverName == null) {
            for (c in candidates.filter { !it.endsWith(":8096") }) {
                if (withContext(Dispatchers.IO) { NextPvrBackend.answers(c) }) {
                    base = c
                    nextPvr = true
                    break
                }
            }
        }
        serverChecked = true
    }

    // Quick Connect: ask for a code, then poll until someone approves it. A code asked for in the
    // last few minutes is kept (the screen can be rebuilt, e.g. when the app is opened again from
    // the home screen), so the one someone is typing in the web client stays the one shown.
    LaunchedEffect(base, attempt, usePassword, serverChecked, nextPvr) {
        if (nextPvr) message = null
        val address = base
        if (usePassword || address == null || !serverChecked || nextPvr) return@LaunchedEffect
        code = null
        try {
            val api = Jdvr.jellyfin.createApi(baseUrl = address)
            val kept = PendingCode.takeIf { it?.server == address && it.attempt == attempt && System.currentTimeMillis() - it.at < 9 * 60_000 }
            var state =
                kept?.let { k -> runCatching { withContext(Dispatchers.IO) { api.quickConnectApi.getQuickConnectState(secret = k.secret).content } }.getOrNull() }
                    ?: withContext(Dispatchers.IO) { api.quickConnectApi.initiateQuickConnect().content }.also {
                        PendingCode = PendingQuickConnect(address, attempt, it.secret, System.currentTimeMillis())
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
            message = "Can't reach Quick Connect on $address (${e.message}). Check the address, or sign in with a password."
        }
    }

    Box(Modifier.fillMaxSize().screenBackground(), contentAlignment = Alignment.Center) {
        Column(
            // Scrolls when the warnings and the password boxes make it taller than the screen.
            modifier = Modifier.width(620.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).background(Jc.Panel, RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    nextPvr -> "Sign in to NextPVR"
                    serverName != null -> "Sign in to Jellyfin"
                    else -> "Sign in"
                },
                color = Jc.Text,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { Field("Server", server, onChange = { server = it }, keyboard = KeyboardType.Uri) }
                if (server.isNotEmpty()) Button("Clear") { server = "" }
            }
            // Which server this is, and a warning for an unencrypted address outside the home network.
            if (server.isBlank()) {
                Text("Jellyfin or NextPVR address, e.g. 192.168.1.50 (http:// and the usual port are added).", color = Jc.Muted, fontSize = 15.sp)
            } else if (!serverChecked) {
                Text("Checking…", color = Jc.Muted, fontSize = 15.sp)
            } else {
                Text(
                    when {
                        serverName != null -> "Jellyfin: $serverName at $base"
                        nextPvr -> "NextPVR at $base"
                        else -> "No Jellyfin or NextPVR server answers at this address."
                    },
                    color = if (base != null) Jc.Muted else Jc.Amber,
                    fontSize = 15.sp,
                )
            }
            if (insecureRemote(target)) {
                Text(
                    "This address isn't on your home network and uses http, so your sign-in and everything you watch would cross the internet unencrypted. Use the server's https address instead.",
                    color = Jc.Amber,
                    fontSize = 15.sp,
                )
            }
            // Found on the network: pick one (the one in use isn't listed).
            val others = found.filter { it.address != base }
            if (others.isNotEmpty()) {
                Text("On your network", color = Jc.Muted, fontSize = 14.sp)
                others.take(6).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { s -> Button("${s.kind} – ${s.name}") { server = s.address } }
                    }
                }
            }
            if (server.isBlank()) {
                Text(
                    when {
                        searching -> "Looking for Jellyfin and NextPVR servers on your network…"
                        found.isEmpty() -> "No server found on the network. Type its address."
                        else -> "Pick a server, or type its address."
                    },
                    color = Jc.Muted,
                    fontSize = 16.sp,
                )
                if (!searching) Button("Look again") { search++ }
            } else if (nextPvr) {
                Text("Your PIN goes to NextPVR at $target.", color = Jc.Muted, fontSize = 15.sp)
                Field("PIN", pin, onChange = { pin = it }, keyboard = KeyboardType.NumberPassword, secret = true)
                Button(if (insecureRemote(target) && !armedInsecure) "Sign in anyway (unencrypted)" else "Sign in", primary = true) {
                    if (insecureRemote(target) && !armedInsecure) {
                        armedInsecure = true
                        return@Button
                    }
                    scope.launch {
                        try {
                            val address = target
                            val pinMd5 = NextPvrBackend.md5(pin)
                            withContext(Dispatchers.IO) { NextPvrBackend.login(address, pinMd5) }
                            Jdvr.signInNextPvr(address, pinMd5)
                            onSignedIn()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Sign-in failed: ${e.message}"
                        }
                    }
                }
            } else if (!usePassword) {
                if (code != null) {
                    Text("Approve this code from the Jellyfin web client: your profile picture > Quick Connect.", color = Jc.Muted, fontSize = 16.sp)
                    Text(code!!.chunked(3).joinToString(" "), color = Jc.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                    Text("Waiting for approval…", color = Jc.Muted, fontSize = 15.sp)
                } else if (message == null && base != null) {
                    Text("Getting a Quick Connect code…", color = Jc.Muted, fontSize = 16.sp)
                }
                if (base != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button("New code") { attempt++ }
                        Button("Use a password instead") { usePassword = true }
                    }
                }
            } else {
                // Say where the password is going (discovery trusts whoever answers on the network).
                Text("Your password goes to ${serverName ?: "the server"} at $target.", color = Jc.Muted, fontSize = 15.sp)
                Field("User", user, onChange = { user = it })
                Field("Password", password, onChange = { password = it }, keyboard = KeyboardType.Password, secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        if (insecureRemote(target) && !armedInsecure) "Sign in anyway (unencrypted)" else "Sign in",
                        primary = true,
                    ) {
                        // Unencrypted over the internet: a second press, after reading the warning.
                        if (insecureRemote(target) && !armedInsecure) {
                            armedInsecure = true
                            return@Button
                        }
                        if (serverName == null) {
                            message = "No Jellyfin server answers at $target: check the address."
                            return@Button
                        }
                        scope.launch {
                            try {
                                val api = Jdvr.jellyfin.createApi(baseUrl = target)
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

// A server that answered on the network: "Jellyfin" or "NextPVR", its name, its address.
private data class FoundServer(
    val kind: String,
    val name: String,
    val address: String,
)

/**
 * The addresses to try for what was typed: http:// added when there's no scheme; with no port (and
 * no path) also the usual ports, 8096 for Jellyfin and 8866 for NextPVR.
 */
internal fun serverCandidates(typed: String): List<String> {
    val t = typed.trim().trimEnd('/')
    if (t.isEmpty()) return emptyList()
    val url = if (t.contains("://")) t else "http://$t"
    val rest = url.substringAfter("://")
    if (rest.isEmpty() || rest.contains('/')) return listOf(url)
    val hasPort = if (rest.startsWith("[")) rest.substringAfter(']').startsWith(":") else rest.contains(':')
    return if (hasPort) listOf(url) else listOf(url, "$url:8096", "$url:8866")
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

/**
 * An http address outside the home network: a hostname with a dot that isn't a local name, or an
 * IP address that isn't private, loopback or link-local. https, and anything on the home network,
 * is fine.
 */
internal fun insecureRemote(address: String): Boolean {
    val url = address.trim()
    if (!url.startsWith("http://", ignoreCase = true)) return false
    val host = url.removePrefix("http://").removePrefix("HTTP://").substringBefore('/').let { h ->
        if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
    }.lowercase()
    if (host.isEmpty()) return false
    val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && host.count { c -> c == '.' } == 3 }
    if (ipv4 != null) {
        val (a, b) = ipv4[0] to ipv4[1]
        val private = a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        return !private
    }
    if (host.contains(':')) {
        // IPv6: loopback, unique local (fc00::/7) and link-local (fe80::/10) are home.
        return !(host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb"))
    }
    val localName = !host.contains('.') || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".home.arpa") || host.endsWith(".internal")
    return !localName
}
