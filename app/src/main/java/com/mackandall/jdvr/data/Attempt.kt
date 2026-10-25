package com.mackandall.jdvr.data

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * A server call for a screen: its answer, or null when it failed. Failures are logged under
 * "JDVR" (`adb logcat -s JDVR` shows why something didn't appear), and cancellation still cancels,
 * which runCatching would swallow.
 */
suspend fun <T> attempt(block: suspend () -> T): T? = attemptResult(block).getOrNull()

/** The same, as a Result, for a screen that shows what went wrong. */
suspend fun <T> attemptResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("JDVR", "Server call failed", e)
        Result.failure(e)
    }

/** The same without the log, for a call whose failure is an answer (a probe for a missing plugin). */
suspend fun <T> attemptQuietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
