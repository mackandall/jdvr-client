package com.mackandall.jdvr.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTransportTest {
    @Test
    fun behindIsEdgeMinusPositionNeverNegative() {
        assertEquals(60_000L, recordingBehind(edgeMs = 600_000, positionMs = 540_000))
        assertEquals(0L, recordingBehind(edgeMs = 600_000, positionMs = 610_000))
    }

    @Test
    fun forwardJumpsThirtySecondsUntilNearLive() {
        assertEquals(570_000L, recordingForward(edgeMs = 600_000, positionMs = 540_000))
        // Within 30 s of the live edge: go live instead.
        assertNull(recordingForward(edgeMs = 600_000, positionMs = 575_000))
        assertNull(recordingForward(edgeMs = 600_000, positionMs = 570_000))
    }
}
