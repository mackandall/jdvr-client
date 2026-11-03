package com.mackandall.jdvr.player

import com.mackandall.jdvr.data.Segment
import com.mackandall.jdvr.data.Upcoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UpNextTest {
    private val min = 60_000L

    private fun ad(
        from: Long,
        to: Long,
    ) = Segment("Commercial", from, to)

    @Test
    fun creditsMarkWins() {
        val segs = listOf(ad(22 * min, 25 * min), Segment("Outro", 21 * min, 22 * min))
        assertEquals(UpNextTiming(21 * min, "credits mark"), upNextTiming(25 * min, segs, 40_000, 45_000))
    }

    @Test
    fun beforeTheLastBreak() {
        // Seinfeld: show to 22:30, the last break to the end of the 25-minute file.
        val segs = listOf(ad(8 * min, 10 * min), ad(22 * min + 30_000, 25 * min))
        assertEquals(22 * min - 10_000, upNextTiming(25 * min, segs, 40_000, 45_000)!!.showAtMs)
    }

    @Test
    fun lastBreakFollowedByPaddingStillCounts() {
        val segs = listOf(ad(28 * min, 30 * min))
        assertEquals(28 * min - 40_000, upNextTiming(31 * min, segs, 40_000, 45_000)!!.showAtMs)
    }

    @Test
    fun splitLastBreakIsJoined() {
        val segs = listOf(ad(27 * min, 28 * min), ad(28 * min + 10_000, 30 * min))
        assertEquals(27 * min, finalBreakStart(30 * min, segs))
    }

    @Test
    fun breakEndingLongBeforeTheEndIsNotTheLast() {
        // A cut archive: its last mark is mid-show.
        val segs = listOf(ad(20 * min, 22 * min))
        val t = upNextTiming(30 * min, segs, 40_000, 45_000)!!
        assertEquals("end of the file", t.source)
        assertEquals(30 * min - 45_000, t.showAtMs)
    }

    @Test
    fun neverInTheFirstHalf() {
        // A "last break" from the first half is no show's end: the end of the file is used.
        val segs = listOf(ad(2 * min, 10 * min))
        assertEquals("end of the file", upNextTiming(10 * min, segs, 40_000, 45_000)!!.source)
        assertEquals(5 * min, upNextTiming(10 * min, emptyList(), 40_000, 8 * min)!!.showAtMs)
        assertNull(upNextTiming(0, segs, 40_000, 45_000))
    }

    @Test
    fun gaps() {
        assertNull(missingEpisodes(4, 11, 4, 12))
        assertEquals("S4:E13 not recorded", missingEpisodes(4, 12, 4, 14))
        assertEquals("S4:E13–E15 not recorded", missingEpisodes(4, 12, 4, 16))
        assertEquals("S5:E1–E2 not recorded", missingEpisodes(4, 24, 5, 3))
        assertNull(missingEpisodes(4, 24, 5, 1))
        assertNull(missingEpisodes(null, 3, 4, 5))
    }

    @Test
    fun nextScheduledPicksTheShowsEarliestFutureRecording() {
        val now = Instant.parse("2026-11-02T12:00:00Z")
        val list =
            listOf(
                Upcoming(name = "Seinfeld", seasonEpisode = "S4:E16", episodeTitle = "The Shoes", start = "2026-11-06T00:00:00Z", channelName = "WPIX"),
                Upcoming(name = "Seinfeld", seasonEpisode = "S4:E15", episodeTitle = "The Visa", start = "2026-11-05T00:00:00Z", channelName = "WPIX"),
                Upcoming(name = "Seinfeld", seasonEpisode = "S4:E14", start = "2026-11-01T00:00:00Z"),
                Upcoming(name = "Archer", start = "2026-11-03T00:00:00Z"),
            )
        val s = nextScheduled("seinfeld", list, now)!!
        assert(s.startsWith("S4:E15 The Visa · ")) { s }
        assert(s.endsWith(" · WPIX")) { s }
        assertNull(nextScheduled("Frasier", list, now))
    }
}
