package com.mackandall.jdvr.ui

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/** Focuses now if it's on screen: false when its element isn't there (yet, or any more). */
fun FocusRequester.tryFocus(): Boolean =
    try {
        requestFocus()
        true
    } catch (e: IllegalStateException) {
        false
    }

/**
 * Focuses as soon as it's on screen, trying for [withinMs]: a dialog's window, or the row that
 * replaces a loading message, needs a frame or two first. False if it never appeared.
 */
suspend fun FocusRequester.focusSoon(withinMs: Long = 1000): Boolean {
    repeat((withinMs / 50).toInt()) {
        if (tryFocus()) return true
        delay(50)
    }
    return false
}
