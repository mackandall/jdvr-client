package com.mackandall.jdvr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mackandall.jdvr.data.Jdvr
import com.mackandall.jdvr.data.RecordingRule
import com.mackandall.jdvr.data.RulesList
import com.mackandall.jdvr.data.attempt
import com.mackandall.jdvr.data.attemptResult
import com.mackandall.jdvr.data.plainId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Series screen: JellyDVR Schedule's Recording rules (series recordings and Recording
 * Favourites in one list, with pause, priority and what they'll record next). Without that
 * plugin (or with an older one), the plain two-column screen.
 */
@Composable
fun SeriesScreen(
    entry: FocusRequester,
    onOpenShow: (String) -> Unit = {},
) {
    var rules by remember { mutableStateOf<RulesList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        rules = attempt { Jdvr.backend.rules() }
        loaded = true
    }
    when {
        !loaded -> Text("Loading…", color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.padding(28.dp))
        rules == null -> LegacySeriesScreen(entry)
        else -> RulesScreen(rules!!, entry, onOpenShow) { reload++ }
    }
}

// The server finishes in the background: look again shortly, and a few times more. Resuming
// re-arms timers (~10 s) and the server then re-reads them (~12 s).
private suspend fun reloadAfterAction(onReload: () -> Unit) {
    listOf(1500L, 7000L, 15000L, 22000L).forEach {
        delay(it)
        onReload()
    }
}

/** The rule last looked at, for coming back to this screen. */
private var lastRuleId: String? = null

private enum class RuleTab(
    val label: String,
    /** The rules the tab shows (null: all of them, sorted its way); a tab with a filter shows its count. */
    val filter: ((RecordingRule) -> Boolean)? = null,
) {
    Priority("Priority"),
    Name("A–Z"),
    Next("Next airing"),
    Paused("Paused", { it.state == "paused" || it.state == "off" }),
    NotInGuide("Not in guide", { it.state == "not-in-guide" || it.state == "nothing-to-record" }),
}

private fun RulesList.priorityOrder() = rules.sortedBy { it.priority }.map { it.id }

@Composable
private fun RulesScreen(
    list: RulesList,
    entry: FocusRequester,
    onOpenShow: (String) -> Unit,
    onReload: () -> Unit,
) {
    var tab by remember { mutableStateOf(RuleTab.Priority) }
    var note by remember { mutableStateOf<String?>(null) }
    // Back from a show's recordings or the options: the rule you left from, not the top of the list.
    var focusedId by remember { mutableStateOf(lastRuleId) }
    // Priority: a rule picked up with Hold OK, moved with ▲ ▼, put down with OK.
    var moving by remember { mutableStateOf<String?>(null) }
    var order by remember(list) { mutableStateOf(list.priorityOrder()) }
    var pausing by remember { mutableStateOf<RecordingRule?>(null) }
    var editing by remember { mutableStateOf<SeriesTimerInfoDto?>(null) }
    var inActions by remember { mutableStateOf(false) }
    val rowFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    val actionsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()

    // The reloads after the last action (a new action starts them again rather than piling up).
    var reloads by remember { mutableStateOf<Job?>(null) }

    fun act(block: suspend () -> String?) {
        scope.launch {
            note = attemptResult { block() }.getOrElse { "Didn't work: ${it.message ?: it.javaClass.simpleName}" } ?: "Didn't work: the server didn't answer"
            reloads?.cancel()
            reloads = scope.launch { reloadAfterAction(onReload) }
        }
    }

    editing?.let { t ->

        BackHandler { editing = null }
        SeriesEditor(
            timer = t,
            onSave = { changed ->
                editing = null
                act {
                    Jdvr.backend.updateSeriesTimer(changed)
                    "Saved: ${changed.name}"
                }
            },
            onCancel = { editing = null },
        )
        return
    }

    val byId = list.rules.associateBy { it.id }
    val shown =
        when (tab) {
            RuleTab.Priority -> order.mapNotNull { byId[it] }
            RuleTab.Name -> list.rules.sortedBy { it.name.lowercase() }
            RuleTab.Next -> list.rules.sortedWith(compareBy<RecordingRule> { it.nextStart() ?: Instant.MAX }.thenBy { it.name.lowercase() })
            RuleTab.Paused, RuleTab.NotInGuide -> list.rules.filter(tab.filter!!).sortedBy { it.name.lowercase() }
        }
    val focused = byId[focusedId] ?: shown.firstOrNull()
    // Captured when the screen opens: the entry focus on row 1 overwrites lastRuleId straight away.
    val returnTo = remember { lastRuleId }
    LaunchedEffect(Unit) {
        val i = shown.indexOfFirst { it.id == returnTo }
        if (i > 0) {
            state.scrollToItem(i)
            // After the screen's own entry focus (row 1), and once the row is laid out.
            delay(250)
            repeat(30) {
                if (rowFocus[shown[i].id]?.tryFocus() == true) return@LaunchedEffect
                delay(50)
            }
        }
    }


    BackHandler(enabled = inActions || moving != null) {
        if (moving != null) {
            moving = null
            order = list.priorityOrder()
        }
        inActions = false
        focused?.let { rowFocus[it.id]?.tryFocus() }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 22.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recording rules", color = Jc.Title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                note ?: if (moving != null) "▲ ▼ moves it · OK puts it down · Back cancels" else "${list.rules.size} rules · OK: its actions" + if (tab == RuleTab.Priority && list.canManage) " · Hold OK: move it" else "",
                color = if (moving != null) Jc.Focus else Jc.Muted,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RuleTab.entries.forEach { t ->
                val count = t.filter?.let { list.rules.count(it) } ?: -1
                if (count == 0) return@forEach
                Button(if (count > 0) "${t.label} $count" else t.label, primary = tab == t, compact = true) {
                    tab = t
                    moving = null
                }
            }
        }
        Spacer(12)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(
                state = state,
                modifier =
                    Modifier
                        .width(430.dp)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { e ->
                            val id = moving ?: return@onPreviewKeyEvent false
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter)
                            val i = order.indexOf(id)
                            when (e.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    val j = if (e.key == Key.DirectionUp) i - 1 else i + 1
                                    if (i >= 0 && j in order.indices) {
                                        order = order.toMutableList().also { it.add(j, it.removeAt(i)) }
                                        scope.launch { state.animateScrollToItem((j - 2).coerceAtLeast(0)) }
                                    }
                                    true
                                }

                                Key.DirectionCenter, Key.Enter -> {
                                    moving = null
                                    val saved = order
                                    act { Jdvr.backend.setRulePriority(saved)?.message }
                                    true
                                }

                                else -> {
                                    true
                                }
                            }
                        },
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 40.dp, top = 2.dp),
            ) {
                if (shown.isEmpty()) {
                    item { Text(if (list.rules.isEmpty()) "No recording rules yet. Record a series from the guide." else "Nothing here.", color = Jc.Muted, fontSize = 15.sp) }
                }
                items(shown, key = { it.id }) { r ->
                    val fr = rowFocus.getOrPut(r.id) { FocusRequester() }
                    RuleRow(
                        r,
                        rank = if (tab == RuleTab.Priority) order.indexOf(r.id) + 1 else null,
                        moving = moving == r.id,
                        modifier = (if (r == shown.first()) Modifier.focusRequester(entry) else Modifier).focusRequester(fr),
                        onFocus = {
                            focusedId = r.id
                            lastRuleId = r.id
                            inActions = false
                        },
                        onClick = {
                            if (moving == null) {
                                inActions = true
                                actionsFocus.tryFocus()
                            }
                        },
                        onHold =
                            if (tab == RuleTab.Priority && list.canManage) {
                                { moving = r.id }
                            } else {
                                null
                            },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(bottom = 24.dp)) {
                focused?.let { r ->
                    RuleDetail(
                        r,
                        total = list.rules.size,
                        // While one is being moved, its place in the list as it now stands.
                        rank = if (r.priority > 0) order.indexOf(r.id) + 1 else 0,
                        canManage = list.canManage,
                        actionsFocus = actionsFocus,
                        onPause = { pausing = r },
                        onResume = {
                            act {
                                if (r.isFavourite) {
                                    Jdvr.backend.setFavouriteEnabled(r.favouriteId, true)?.message
                                } else {
                                    Jdvr.backend.resumeSeries(r.id)?.message
                                }
                            }
                        },
                        onOptions = {
                            scope.launch {
                                editing = attempt { Jdvr.backend.seriesTimers() }?.firstOrNull { it.id?.plainId() == r.id }
                                if (editing == null) note = "Couldn't load the series recording's options."
                            }
                        },
                        onRecordings = { onOpenShow(r.name) },
                        onRemove = {
                            act {
                                if (r.isFavourite) {
                                    Jdvr.backend.removeFavourite(r.favouriteId)?.message
                                } else {
                                    Jdvr.backend.cancelSeries(r.id)
                                    "Series recording removed: ${r.name}"
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pausing?.let { r ->
        PauseSheet(r, onDismiss = { pausing = null }) { until, mode ->
            pausing = null
            act {
                when {
                    r.isFavourite -> Jdvr.backend.setFavouriteEnabled(r.favouriteId, false, until)?.message
                    else -> Jdvr.backend.pauseSeries(r.id, until, mode)?.message
                }
            }
        }
    }
}

@Composable
private fun RuleArt(
    r: RecordingRule,
    width: Int,
    height: Int,
) {
    val url = r.image?.let { Jdvr.backend.imageUrl(it.itemId, it.type, height * 3, it.tag) }
    Box(
        Modifier
            .width(width.dp)
            .height(height.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B3A52), Color(0xFF121A26)))),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(r.name, color = Color.White, fontSize = (height / 4).coerceIn(9, 16).sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(5.dp))
        }
        if (r.state == "paused") {
            Box(Modifier.fillMaxSize().background(Color(0x8C0A0804)), contentAlignment = Alignment.Center) {
                Text("❚❚", color = Jc.Amber, fontSize = (height / 3).sp)
            }
        }
    }
}

@Composable
private fun RuleRow(
    r: RecordingRule,
    rank: Int?,
    moving: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onHold: (() -> Unit)?,
) {
    Surface(
        onClick = onClick,
        onLongClick = onHold,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (moving) Jc.FocusFill else Jc.Panel,
                focusedContainerColor = Jc.FocusFill,
                contentColor = Jc.Text,
                focusedContentColor = Jc.Text,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (moving) Border(BorderStroke(2.dp, Jc.Amber)) else Border.None,
                focusedBorder = Border(BorderStroke(2.dp, if (moving) Jc.Amber else Jc.Focus)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RuleArt(r, 74, 42)
            Column(Modifier.weight(1f)) {
                Text(
                    (rank?.let { "$it · " } ?: "") + r.name,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (r.state == "off") Jc.Muted else Jc.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (moving) "Moving: ▲ ▼, then OK" else r.summary(), color = if (moving) Jc.Amber else Jc.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                r.state == "paused" -> Chip("paused", Jc.Amber)
                r.isFavourite -> Text("★", color = if (r.state == "off") Jc.Muted else Jc.Gold, fontSize = 15.sp)
                r.upcoming > 0 -> Chip("${r.upcoming}", Jc.Muted)
                else -> {}
            }
        }
    }
}

@Composable
private fun RuleDetail(
    r: RecordingRule,
    total: Int,
    rank: Int,
    canManage: Boolean,
    actionsFocus: FocusRequester,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOptions: () -> Unit,
    onRecordings: () -> Unit,
    onRemove: () -> Unit,
) {
    var armed by remember(r.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Jc.Panel)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RuleArt(r, 170, 96)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.name, color = Jc.Title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (r.isFavourite) "Recording Favourite" else "Series recording") + " · " + r.details + if (rank > 0) " · priority $rank of $total" else "",
                    color = Jc.Muted,
                    fontSize = 13.sp,
                    maxLines = 3,
                )
                Text(r.stateLine(), color = if (r.state == "paused") Jc.Amber else Jc.Text, fontSize = 14.sp)
            }
        }
        if (r.next.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                r.next.forEach { a ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(a.start.whenShort(), color = Jc.Muted, fontSize = 13.5.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(168.dp))
                        Text(
                            (if (a.held) "❚❚ " else if (a.now) "● " else "") + (a.episode?.takeIf { it.isNotBlank() } ?: a.channel) + if (a.held) " (on hold)" else if (a.now) " (recording now)" else "",
                            color =
                                when {
                                    a.held -> Jc.Amber
                                    a.now -> Jc.Recording
                                    else -> Jc.Text
                                },
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Five buttons don't fit one line beside a paused rule: the rest go on a second line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val first = Modifier.focusRequester(actionsFocus)
            if (canManage) {
                // One button whose label changes (Resume now → Pause…), so focus stays on it when a reload flips the state.
                val paused = r.state == "paused" || r.state == "off"
                Button(
                    when {
                        r.state == "paused" -> "Resume now"
                        r.state == "off" -> "Switch on"
                        r.isFavourite -> "Switch off…"
                        else -> "Pause…"
                    },
                    primary = paused,
                    compact = true,
                    modifier = first,
                    onClick = if (paused) onResume else onPause,
                )
                if (paused) Button(if (r.state == "off") "Change" else "Change pause", compact = true, onClick = onPause)
                if (!r.isFavourite) Button("Options", compact = true, onClick = onOptions)
            }
            Button("Recordings (${r.recorded})", compact = true, modifier = if (canManage) Modifier else first) { if (r.recorded > 0) onRecordings() }
            if (canManage) {
                Button(if (armed) "Press again" else "Remove", compact = true) { if (armed) onRemove() else armed = true }
            }
        }
        Text("Back: to the list" + if (r.unwatched > 0) " · ${r.unwatched} unwatched" else "", color = Jc.Muted, fontSize = 12.5.sp)
    }
}

/** How long to pause (or switch off): each choice says when it comes back. */
@Composable
private fun PauseSheet(
    r: RecordingRule,
    onDismiss: () -> Unit,
    onPick: (until: String?, mode: String?) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusSoon() }
    fun weeks(n: Long) =
        LocalDate
            .now()
            .plusWeeks(n)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    val nextUp = r.next.firstOrNull()
    val choices =
        buildList {
            add(Triple(if (r.isFavourite) "Until I switch it on" else "Until I resume it", if (r.upcoming + r.held > 0) "${r.upcoming + r.held} upcoming held" else "") { onPick(null, null) })
            if (!r.isFavourite && nextUp != null) add(Triple("Skip the next episode", nextUp.start.whenShort()) { onPick(null, "next") })
            listOf(1L, 2L, 4L, 8L).forEach { n ->
                val at = weeks(n)
                add(Triple(if (n == 1L) "For 1 week" else "For $n weeks", "back ${at.dayShort()}") { onPick(at.toString(), null) })
            }
            if (!r.isFavourite) add(Triple("Until the season starts", "next season premiere") { onPick(null, "premiere") })
        }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .heightIn(max = 500.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Jc.Sheet)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text((if (r.isFavourite) "Switch off " else "Pause ") + r.name, color = Jc.Title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (r.isFavourite) "Its upcoming recordings are cancelled, and come back when it's on. Back closes this." else "Its settings stay as they are. Back closes this.",
                color = Jc.Muted,
                fontSize = 13.5.sp,
            )
            choices.forEachIndexed { i, (label, hint, pick) ->
                PauseChoice(label, hint, if (i == 0) Modifier.focusRequester(first) else Modifier, pick)
            }
        }
    }
}

// One line: the choice, and when the rule comes back.
@Composable
private fun PauseChoice(
    label: String,
    hint: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Jc.Panel, focusedContainerColor = Jc.FocusFill, contentColor = Jc.Text, focusedContentColor = Jc.Text),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Jc.Focus))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(hint, color = Jc.Muted, fontSize = 14.sp, maxLines = 1)
        }
    }
}

private fun RecordingRule.nextStart(): Instant? = next.firstOrNull { !it.held }?.start?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun RecordingRule.summary(): String =
    when (state) {
        "paused" -> if (untilPremiere) "Paused until the season starts" else until?.let { "Paused until ${it.dayShort()}" } ?: "Paused"
        "off" -> until?.let { "Off until ${it.dayShort()}" } ?: "Off"
        "not-in-guide" -> "Not in the guide"
        "nothing-to-record" -> "Nothing to record"
        else -> next.firstOrNull { !it.held }?.let { "Next ${it.start.whenShort()} · ${it.channel}" } ?: "Recording"
    }

private fun RecordingRule.stateLine(): String =
    when (state) {
        "paused" ->
            (if (untilPremiere) "Paused until the next season starts" else until?.let { "Paused until ${it.dayShort()}: it resumes by itself" } ?: "Paused: nothing records until resumed") +
                if (held > 0) " · $held on hold" else ""
        "off" -> until?.let { "Off until ${it.dayShort()}: it switches back on by itself" } ?: "Off: records nothing until switched on"
        "not-in-guide" -> "No airings in the guide"
        "nothing-to-record" -> "In the guide, but nothing to record"
        else -> "$upcoming upcoming" + if (recorded > 0) " · $recorded recorded" else ""
    }

private fun Instant.local(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneId.systemDefault())

// With the time when it isn't midnight (a pause that skips one episode ends just after it starts).
private fun Instant.dayShort(): String = local().let { t -> t.day() + if (t.hour != 0 || t.minute != 0) " " + t.time() else "" }

private fun String.dayShort(): String = runCatching { Instant.parse(this).dayShort() }.getOrDefault(this)

private fun String.whenShort(): String =
    runCatching {
        val t = Instant.parse(this).local()
        when (t.toLocalDate()) {
            LocalDate.now() -> "Today " + t.time()
            LocalDate.now().plusDays(1) -> "Tomorrow " + t.time()
            else -> t.day() + " " + t.time()
        }
    }.getOrDefault(this)
