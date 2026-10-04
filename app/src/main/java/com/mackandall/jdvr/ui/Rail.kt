package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mackandall.jdvr.Screen

/** The left rail, opened over the screen (dimmed behind it); focus starts on the current section. */
@Composable
fun Rail(
    selected: Screen,
    onSelect: (Screen) -> Unit,
    onSignOut: () -> Unit,
    libraries: List<com.mackandall.jdvr.data.LiveTvRepository.Library> = emptyList(),
    selectedLibrary: java.util.UUID? = null,
    onSelectLibrary: (com.mackandall.jdvr.data.LiveTvRepository.Library) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    // Every item in on-screen order (sections up to Recorded Shows, the libraries, the rest,
    // Settings, Sign out), each with a stable focus handle; ▲▼ go to the item above/below
    // explicitly, so focus never slips out of the rail into the screen behind.
    val sections = Screen.entries.filter { it != Screen.Settings && it != Screen.Library }
    val split = sections.indexOf(Screen.RecordedShows) + 1
    data class Entry(val label: String, val on: Boolean, val click: () -> Unit)
    val entries =
        sections.take(split).map { s -> Entry(s.label, s == selected) { onSelect(s) } } +
            libraries.map { l -> Entry(l.name, selected == Screen.Library && l.id == selectedLibrary) { onSelectLibrary(l) } } +
            sections.drop(split).map { s -> Entry(s.label, s == selected) { onSelect(s) } } +
            listOf(Entry(Screen.Settings.label, selected == Screen.Settings) { onSelect(Screen.Settings) }, Entry("Sign out", false, onSignOut))
    val count = entries.size
    val reqs = remember(count) { List(count) { FocusRequester() } }
    val current = reqs[entries.indexOfFirst { it.on }.coerceAtLeast(0)]
    fun slot(i: Int): Modifier =
        Modifier.focusRequester(reqs[i]).focusProperties {
            up = if (i > 0) reqs[i - 1] else FocusRequester.Cancel
            down = if (i < count - 1) reqs[i + 1] else FocusRequester.Cancel
            left = FocusRequester.Cancel
        }
    var hadFocus by remember { mutableStateOf(false) }
    // Keep asking until the rail really has focus: a screen still settling (a grid loading,
    // restoring its focus) can take it back, and then the rail would never notice focus leaving.
    LaunchedEffect(Unit) {
        repeat(30) {
            if (hadFocus) return@LaunchedEffect
            runCatching { current.requestFocus() }
            kotlinx.coroutines.delay(50)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            modifier =
                Modifier
                    .onFocusChanged {
                        if (hadFocus && !it.hasFocus) onDismiss()
                        hadFocus = it.hasFocus
                    }.width(220.dp)
                    .fillMaxHeight()
                    .background(Jc.Rail)
                    .padding(horizontal = 14.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
            // One scrolling list (no boundary for focus to cross), dividers around the libraries and
            // above Settings.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEachIndexed { i, e ->
                    val divider = (libraries.isNotEmpty() && (i == split || i == split + libraries.size)) || i == count - 2
                    if (divider) Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp).width(168.dp).height(1.dp).background(Jc.Line))
                    RailItem(e.label, e.on, slot(i), e.click)
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(192.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Jc.Panel2 else Jc.Rail,
                focusedContainerColor = Jc.FocusFill,
                contentColor = if (selected) Jc.Text else Jc.Muted,
                focusedContentColor = Jc.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Text(label, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).align(Alignment.CenterStart))
    }
}
