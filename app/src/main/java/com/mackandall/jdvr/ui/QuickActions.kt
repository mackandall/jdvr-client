package com.mackandall.jdvr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.LiveTvRepository
import com.mackandall.jdvr.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Holding OK does something of its own (Settings › Remote); off, a hold is just a press. */
val holdEnabled: Boolean get() = Jdvr.prefs.holdOk != Prefs.HOLD_OFF

/**
 * What holding OK (or the Menu key) on a poster, card or episode does: the quick actions menu, or
 * the details page, as chosen in Settings › Remote.
 */
fun holdOk(
    menu: () -> Unit,
    details: () -> Unit,
) = if (Jdvr.prefs.holdOk == Prefs.HOLD_DETAILS) details() else menu()

/** The Menu key on the remote, where there is one: does what holding OK does. */
fun Modifier.onMenuKey(action: () -> Unit): Modifier =
    onPreviewKeyEvent { k ->
        if (k.type == KeyEventType.KeyDown && (k.key == Key.Menu || k.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_MENU)) {
            action()
            true
        } else {
            false
        }
    }

/**
 * A movie, episode or recording's quick actions, over the screen it was opened from: play (or
 * resume, or from the start), details, mark watched, and [extra] (the recording actions:
 * favourites, archive, delete — those that change something ask for a second press as they do
 * on the details page). A show gets Open show and Mark watched. Back or Close leaves it.
 * [onChanged] follows a change the list should show (watched, deleted, moved).
 */
@Composable
fun QuickActions(
    itemId: UUID,
    onPlay: (fromStart: Boolean) -> Unit,
    onDetails: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    // A show: Open show instead of play and details.
    onOpenShow: (() -> Unit)? = null,
    extra: (@Composable (LiveTvRepository.ItemDetails, (String) -> Unit, () -> Unit) -> Unit)? = null,
    // Opened from the show's own page (its Show actions button): no Open show.
    onShowPage: Boolean = false,
) {
    var d by remember(itemId) { mutableStateOf<LiveTvRepository.ItemDetails?>(null) }
    var failed by remember(itemId) { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // Close comes first in the dialog and would take focus by default: focus goes on to Play.
    var firstFocused by remember { mutableStateOf(false) }
    // The OK that opened the menu is still held: its repeats and its release would press the
    // button that just took focus (Play). OK does nothing here until a fresh press.
    var okFresh by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(itemId) {
        runCatching { Jdvr.liveTv.details(itemId) }.onSuccess { d = it }.onFailure { failed = true }
    }
    LaunchedEffect(note) {
        if (note != null) {
            delay(5000)
            note = null
        }
    }
    // Leaves the menu after something that ends what it was for (played, deleted, moved).
    fun done() {
        onChanged()
        onDismiss()
    }

    val scroll = rememberScrollState()
    // With the recording's (or show's) actions: two columns, the item's own on the left, so the
    // menu stays on the screen however long it gets.
    val twoColumns = extra != null
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(if (twoColumns) 760.dp else 560.dp)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Jc.Sheet)
                .onPreviewKeyEvent { k ->
                    val ok = k.key == Key.DirectionCenter || k.key == Key.Enter || k.key == Key.NumPadEnter
                    when {
                        !ok || okFresh -> false
                        // A new press (not a repeat of the held one): from now on OK works.
                        k.type == KeyEventType.KeyDown && k.nativeKeyEvent.repeatCount == 0 -> {
                            okFresh = true
                            false
                        }
                        // The held press's repeats, and its release: swallowed.
                        else -> true
                    }
                }
                .verticalScroll(scroll)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val det = d
            val item = det?.item
            // Close at the top right, as in the Other airings popup; focus still starts on Play.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item?.let { titleOf(it) } ?: if (failed) "Couldn't load this item" else "Loading…", color = Jc.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item?.let { subtitleOf(it, det) }?.let { Text(it, color = Jc.Muted, fontSize = 15.sp) }
                }
                Button("Close", modifier = if (failed) Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) firstFocused = true } else Modifier, onClick = onDismiss)
            }
            note?.let { Text(it, color = Jc.Focus, fontSize = 15.sp) }
            if (item != null) {
                val watch: @Composable ColumnScope.() -> Unit = {
                    val firstMod = Modifier.fillMaxWidth().focusRequester(first).onFocusChanged { if (it.isFocused) firstFocused = true }
                    if (onShowPage) {
                        // Mark watched, below, is the first button.
                    } else if (onOpenShow != null) {
                        Button("Open show", primary = true, modifier = firstMod) {
                            onDismiss()
                            onOpenShow()
                        }
                    } else {
                        val resume = item.resumeMs > 0 && !item.played
                        Button(if (resume) "Resume from ${item.resumeMs / 60_000} min" else "Play", primary = true, modifier = firstMod) {
                            onDismiss()
                            onPlay(false)
                        }
                        if (resume) {
                            Button("Play from start", modifier = Modifier.fillMaxWidth()) {
                                onDismiss()
                                onPlay(true)
                            }
                        }
                        Button("Details", modifier = Modifier.fillMaxWidth()) {
                            onDismiss()
                            onDetails()
                        }
                    }
                    // A show: every episode of it (in any library), as Jellyfin marks a series.
                    val all = if (item.type == "Series") " (all episodes)" else ""
                    Button(if (item.played) "Mark unwatched$all" else "Mark watched$all", modifier = if (onShowPage) firstMod else Modifier.fillMaxWidth()) {
                        scope.launch {
                            runCatching { Jdvr.liveTv.setPlayed(item.id, !item.played) }
                                .onSuccess {
                                    note = if (item.played) "Marked unwatched" else "Marked watched"
                                    d = det.copy(item = item.copy(played = !item.played, resumeMs = if (item.played) item.resumeMs else 0L))
                                    onChanged()
                                }.onFailure { note = "Couldn't change it: ${it.message}" }
                        }
                    }
                }
                val more: @Composable ColumnScope.() -> Unit = {
                    // "Watch live" closes the menu first: it is a dialog, and would sit over the player.
                    val live = LocalWatchLive.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalWideButtons provides true,
                        LocalWatchLive provides live?.let { w -> { id: java.util.UUID -> onDismiss(); w(id) } },
                    ) {
                        extra?.invoke(det, { note = it }, ::done)
                    }
                }
                if (twoColumns) {
                    MenuColumns(left = watch, right = more)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { watch() }
                }
            }
        }
    }
    LaunchedEffect(d != null, failed) {
        // The dialog's window needs a frame or two before anything in it can take focus, and an
        // early requestFocus silently does nothing: retry until it lands.
        repeat(40) {
            if (firstFocused) return@LaunchedEffect
            runCatching { first.requestFocus() }
            delay(50)
        }
    }
}

private fun titleOf(i: LiveTvRepository.LibItem): String =
    if (i.seriesName != null && i.type == "Episode") "${i.seriesName}: ${i.name}" else i.name

private fun subtitleOf(
    i: LiveTvRepository.LibItem,
    d: LiveTvRepository.ItemDetails?,
): String? =
    listOfNotNull(
        if (i.season != null && i.episode != null) "S${i.season}:E${i.episode}" else null,
        i.year?.toString(),
        i.runtimeMin?.takeIf { it > 0 && i.type != "Series" }?.let { "$it min" },
        d?.location?.takeUnless { Jdvr.isNextPvr },
    ).joinToString(" · ").ifEmpty { null }
