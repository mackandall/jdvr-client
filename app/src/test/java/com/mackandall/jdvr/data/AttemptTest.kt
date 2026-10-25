package com.mackandall.jdvr.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttemptTest {
    @Test
    fun aSuccessIsItsValue() =
        runBlocking {
            assertEquals(3, attempt { 1 + 2 })
            assertEquals(Result.success("ok"), attemptResult { "ok" })
        }

    @Test
    fun aFailureIsNull() =
        runBlocking {
            assertNull(attemptQuietly<Int> { error("server down") })
        }

    @Test(expected = CancellationException::class)
    fun cancellingStillCancels() {
        runBlocking { attemptQuietly<Int> { throw CancellationException("left the screen") } }
    }
}
