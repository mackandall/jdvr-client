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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", color = Jc.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(6)
        Section("Guide")
        Choice(
            label = "Programme details",
            help = "Beside the guide, or above it so the guide uses the full width.",
            value = if (prefs.guideInfo == Prefs.TOP) "Above the guide" else "Beside the guide",
            modifier = Modifier.focusRequester(entry),
        ) { prefs.changeGuideInfo(if (prefs.guideInfo == Prefs.TOP) Prefs.SIDE else Prefs.TOP) }
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
        if (libraries.isNotEmpty()) {
            Section("Libraries in the menu")
            libraries.forEach { l ->
                val hidden = l.id.toString() in prefs.hiddenLibraries
                Choice(
                    label = l.name,
                    help = "A Jellyfin library. New libraries appear in the menu by themselves.",
                    value = if (hidden) "Hidden" else "Shown in the menu",
                ) { prefs.toggleLibrary(l.id.toString()) }
            }
        }
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
