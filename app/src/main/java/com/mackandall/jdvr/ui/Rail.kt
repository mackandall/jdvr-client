package com.mackandall.jdvr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
) {
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            modifier =
                Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Jc.Rail)
                    .padding(horizontal = 14.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("JDVR", color = Jc.Focus, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp, bottom = 14.dp))
            Screen.entries.filter { it != Screen.Settings }.forEach { s ->
                RailItem(s.label, s == selected, if (s == selected) Modifier.focusRequester(current) else Modifier) { onSelect(s) }
            }
            Spacer(Modifier.weight(1f))
            // App-wide preferences sit apart from the content sections, at the bottom.
            RailItem(Screen.Settings.label, selected == Screen.Settings, if (selected == Screen.Settings) Modifier.focusRequester(current) else Modifier) {
                onSelect(Screen.Settings)
            }
            RailItem("Sign out", false, Modifier, onSignOut)
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
        Text(label, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp).align(Alignment.CenterStart))
    }
}
