package com.mackandall.jdvr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/** How long a first press stays armed. */
private const val ARMED_MS = 4000L

/**
 * "Press again to …": the first press arms, a second press within a few seconds acts, and
 * otherwise it disarms itself. [T] says what is armed when one confirm serves several buttons (a
 * row of recordings, servers); a single button uses [Unit] (see [isArmed] and the one-argument
 * [press]).
 */
@Stable
class Confirm<T> internal constructor(
    private val state: MutableState<T?>,
) {
    /** What is waiting for its second press, if anything. */
    val armed: T? get() = state.value

    /** First press on [what] arms it; a second press on it (while armed) disarms and runs [action]. */
    fun press(
        what: T,
        action: () -> Unit,
    ) {
        if (state.value == what) {
            state.value = null
            action()
        } else {
            state.value = what
        }
    }

    fun disarm() {
        state.value = null
    }
}

/** Whether a single button's confirm is armed. */
val Confirm<Unit>.isArmed: Boolean get() = armed != null

/** A single button's press: arms, or (when armed) acts. */
fun Confirm<Unit>.press(action: () -> Unit) = press(Unit, action)

/** A confirm that starts afresh when any of [keys] changes (another item). */
@Composable
fun <T> rememberConfirm(vararg keys: Any?): Confirm<T> {
    val state = remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(state.value) {
        if (state.value != null) {
            delay(ARMED_MS)
            state.value = null
        }
    }
    return remember(state) { Confirm(state) }
}
