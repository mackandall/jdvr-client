package com.mackandall.jdvr.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GiveWayTest {
    private val now = LocalDateTime.of(2026, 11, 17, 19, 59, 0)

    @Test
    fun aRecordingInTheNextMinuteAndAHalfCounts() {
        assertTrue(LivePictures.startsSoon(now.plusSeconds(60), now))
        assertTrue(LivePictures.startsSoon(now.plusSeconds(90), now))
    }

    @Test
    fun oneJustStartedCounts() {
        assertTrue(LivePictures.startsSoon(now.minusSeconds(20), now))
    }

    @Test
    fun laterOrLongStartedDoesNot() {
        assertFalse(LivePictures.startsSoon(now.plusMinutes(5), now))
        assertFalse(LivePictures.startsSoon(now.minusMinutes(2), now))
    }
}
