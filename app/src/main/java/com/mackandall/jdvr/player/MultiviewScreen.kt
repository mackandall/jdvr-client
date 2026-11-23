package com.mackandall.jdvr.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import com.mackandall.jdvr.ui.Button
import com.mackandall.jdvr.ui.Jc
import com.mackandall.jdvr.ui.ignoreOpeningOk
import com.mackandall.jdvr.ui.tryFocus
import kotlinx.coroutines.delay
import java.util.UUID
import android.view.KeyEvent as AndroidKeyEvent

/**
 * Multiview full screen. The D-pad moves the white ring; its picture has the sound. OK opens the
 * picture's menu (watch it full screen, make it big, change its channel, add or remove a picture);
 * channel up/down change the ringed picture's channel. Back watches the ringed channel full screen.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MultiviewScreen(
    onFullScreen: (UUID) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val info = remember { LiveInfo() }
    val focus = remember { FocusRequester() }
    val ui = Multiview.overlays
    var menu by remember { mutableStateOf(false) }
    // The channel list, to add a picture (-1) or change picture i's channel.
    var picking by remember { mutableStateOf<Int?>(null) }
    // Names on the pictures for a few seconds after a key.
    var labelsUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 5000) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        info.loadChannels()
        focus.tryFocus()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    LaunchedEffect(menu, picking) {
        if (!menu && picking == null) {
            delay(50)
            focus.tryFocus()
        }
    }
    OnLeaveApp { onExit() }
    BackHandler(enabled = !menu && picking == null) {
        Multiview.leave()?.let(onFullScreen) ?: onExit()
    }

    val channels = Multiview.channels
    val (aspect, boxes) = multiviewFrame(Multiview.layout, channels.size.coerceAtLeast(1))

    fun step(delta: Int) {
        val all = info.channels
        val c = Multiview.ringed ?: return
        if (all.isEmpty()) return
        val shown = channels.map { it.id }.toSet()
        var i = all.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
        repeat(all.size) {
            i = (i + delta).mod(all.size)
            if (all[i].id !in shown) {
                Multiview.replace(Multiview.ring, all[i])
                return
            }
        }
    }

    // The nearest picture in the D-pad's direction (by the pictures' centres).
    fun moveRing(dx: Int, dy: Int) {
        val from = boxes.getOrNull(Multiview.ring) ?: return
        val fx = from.x + from.w / 2
        val fy = from.y + from.h / 2
        val next =
            boxes.indices
                .filter { it != Multiview.ring }
                .map { i -> Triple(i, boxes[i].x + boxes[i].w / 2 - fx, boxes[i].y + boxes[i].h / 2 - fy) }
                .filter { (_, x, y) -> if (dx != 0) x * dx > 0.01f else y * dy > 0.01f }
                .minByOrNull { (_, x, y) -> if (dx != 0) kotlin.math.abs(x) + 2 * kotlin.math.abs(y) else kotlin.math.abs(y) + 2 * kotlin.math.abs(x) }
                ?.first ?: return
        Multiview.moveRing(next)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || menu || picking != null) return@onKeyEvent false
                labelsUntil = System.currentTimeMillis() + 4000
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> moveRing(-1, 0)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> moveRing(1, 0)
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> moveRing(0, -1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> moveRing(0, 1)
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_MENU -> menu = true
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> step(1)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> step(-1)
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> {
                        Multiview.close()
                        onExit()
                    }
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The frame fitted to the screen (letterboxed), the pictures placed in it.
            val fw = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
            val fh = fw / aspect
            Box(Modifier.size(fw, fh)) {
                when (Multiview.mode) {
                    MultiviewMode.SERVER -> Multiview.serverPlayer?.let { LivePictureView(it, Modifier.fillMaxSize()) }
                    MultiviewMode.TV ->
                        Multiview.pictures.forEachIndexed { i, p ->
                            val b = boxes.getOrNull(i) ?: return@forEachIndexed
                            androidx.compose.runtime.key(p.channel.id) {
                                LivePictureView(p.player, Modifier.offset(fw * b.x, fh * b.y).size(fw * b.w, fh * b.h))
                            }
                        }
                }
                val labels = tick.let { System.currentTimeMillis() < labelsUntil } || menu
                channels.forEachIndexed { i, c ->
                    val b = boxes.getOrNull(i) ?: return@forEachIndexed
                    val ringed = i == Multiview.ring
                    Box(
                        Modifier
                            .offset(fw * b.x, fh * b.y)
                            .size(fw * b.w, fh * b.h)
                            .then(if (ringed && channels.size > 1) Modifier.border(4.dp, Color.White) else Modifier),
                    ) {
                        if (labels || ringed && channels.size > 1 && labels) {
                            Text(
                                listOfNotNull(c.number, c.name).joinToString("  ") + (if (ringed && channels.size > 1) "  🔊" else ""),
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(10.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        // This TV: a picture still tuning, or one that failed.
                        if (Multiview.mode == MultiviewMode.TV) {
                            val p = Multiview.pictures.getOrNull(i)
                            val note =
                                when (p?.state) {
                                    PictureState.OPENING, null -> "Tuning…"
                                    PictureState.FAILED -> "Couldn't play"
                                    else -> null
                                }
                            note?.let { Text(it, color = Jc.Muted, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center)) }
                        }
                    }
                }
            }
        }
        Multiview.busy?.let {
            Text(
                it,
                color = Jc.Text,
                fontSize = 18.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .background(Jc.Ink.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        ui.note?.let {
            Text(
                it,
                color = Jc.Text,
                fontSize = 16.sp,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(24.dp)
                        .background(Jc.Ink.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (menu) {
            TileMenu(
                onClose = { menu = false },
                onFullScreen = {
                    menu = false
                    Multiview.leave()?.let(onFullScreen) ?: onExit()
                },
                onPick = { i ->
                    menu = false
                    picking = i
                },
                onExit = {
                    menu = false
                    Multiview.close()
                    onExit()
                },
            )
        }
        picking?.let { i ->
            if (info.channels.isNotEmpty()) {
                PicturePicker(
                    info.channels,
                    Multiview.ringed?.id ?: UUID(0, 0),
                    onPick = { c ->
                        picking = null
                        if (i < 0) Multiview.add(c) else Multiview.replace(i, c)
                    },
                    onClose = { picking = null },
                    shown = channels.map { it.id }.toSet(),
                    title = if (i < 0) "Add a picture" else "Change this picture",
                    limit =
                        if (Multiview.mode == MultiviewMode.SERVER) {
                            "the server makes up to 4 pictures"
                        } else {
                            "this TV's hardware plays up to ${Multiview.tvMax} pictures"
                        },
                )
            }
        }
    }
}

/** OK on a picture: what to do with it, and with Multiview. */
@Composable
private fun BoxScope.TileMenu(
    onClose: () -> Unit,
    onFullScreen: () -> Unit,
    onPick: (Int) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        first.tryFocus()
    }
    val ring = Multiview.ring
    val c = Multiview.ringed
    val count = Multiview.channels.size
    Column(
        Modifier
            .align(Alignment.Center)
            .width(360.dp)
            // OK opens this menu on its key-down; its release mustn't press Watch full screen.
            .ignoreOpeningOk()
            // Solid: see-through, the ring and the labels under it showed through the buttons.
            .background(Jc.Panel.copy(alpha = 1f), RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Multiview", color = Jc.Title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        c?.let { Text(listOfNotNull(it.number, it.name, it.now?.name).joinToString(" · "), color = Jc.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Button("Watch full screen", primary = true, modifier = Modifier.fillMaxWidth().focusRequester(first)) { onFullScreen() }
        if (count > 1) {
            val isBig = Multiview.layout == MultiviewLayout.BIG && ring == 0
            Button(if (isBig) "Back to equal pictures" else "Make it big", modifier = Modifier.fillMaxWidth()) {
                if (isBig) Multiview.okOnRing() else Multiview.makeBig(ring)
                onClose()
            }
        }
        Button("Change its channel", modifier = Modifier.fillMaxWidth()) { onPick(ring) }
        if (count < Multiview.max) Button("Add a picture", modifier = Modifier.fillMaxWidth()) { onPick(-1) }
        Multiview.fullReason?.let { Text(it, color = Jc.Muted, fontSize = 13.sp) }
        if (count > 1) {
            Button("Remove this picture", modifier = Modifier.fillMaxWidth()) {
                Multiview.remove(ring)
                onClose()
            }
        }
        val other = if (Multiview.mode == MultiviewMode.TV) MultiviewMode.SERVER else MultiviewMode.TV
        Button(if (other == MultiviewMode.SERVER) "Let the server make the pictures" else "Let this TV make the pictures", modifier = Modifier.fillMaxWidth()) {
            Multiview.switchMode(other)
            onClose()
        }
        Button("Close Multiview", modifier = Modifier.fillMaxWidth()) { onExit() }
    }
}
