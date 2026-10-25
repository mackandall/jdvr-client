package com.mackandall.jdvr.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTest {
    @Test
    fun minutesAndSecondsUnderAnHour() {
        assertEquals("0:00", clock(0))
        assertEquals("1:05", clock(65_400))
        assertEquals("59:59", clock(3_599_999))
    }

    @Test
    fun hoursFromAnHour() {
        assertEquals("1:00:00", clock(3_600_000))
        assertEquals("2:03:04", clock((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test
    fun beforeTheStartIsZero() {
        assertEquals("0:00", clock(-5000))
    }

    @Test
    fun tenthsAreCutNotRounded() {
        assertEquals("1:04.1", clockTenths(64.19))
        assertEquals("0:00.0", clockTenths(0.0))
        assertEquals("1:00:00.5", clockTenths(3600.5))
    }
}
