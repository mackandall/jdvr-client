package com.mackandall.jdvr.player

import com.mackandall.jdvr.data.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentBufferTest {
    private fun buffer(
        id: String,
        recording: Boolean,
        itemId: String? = "item-$id",
    ) = Buffer(id = id, itemId = itemId, recording = recording)

    @Test
    fun theLastOneStillRecording() {
        val buffers = listOf(buffer("a", recording = true), buffer("b", recording = true), buffer("c", recording = false))
        assertEquals("b", buffers.currentBuffer()?.id)
    }

    @Test
    fun aFinishedBufferIsNotThisVisit() {
        assertNull(listOf(buffer("a", recording = false)).currentBuffer())
    }

    @Test
    fun oneWithoutItsItemYetIsSkipped() {
        val buffers = listOf(buffer("a", recording = true), buffer("b", recording = true, itemId = null))
        assertEquals("a", buffers.currentBuffer()?.id)
    }

    @Test
    fun noneIsNull() {
        assertNull(emptyList<Buffer>().currentBuffer())
    }
}
