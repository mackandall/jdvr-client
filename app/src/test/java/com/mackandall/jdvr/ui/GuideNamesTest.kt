package com.mackandall.jdvr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideNamesTest {
    @Test
    fun abbreviatesBeforeDroppingTheSubtitle() {
        assertEquals(listOf("Law & Order: Special Victims Unit", "L&O: SVU", "L&O"), GuideNames.tiers("Law & Order: Special Victims Unit"))
    }

    @Test
    fun dropsALeadingArticle() {
        assertEquals(listOf("The King of Queens", "King of Queens"), GuideNames.tiers("The King of Queens"))
    }

    @Test
    fun aShortTitleStaysAsItIs() {
        assertEquals(listOf("Seinfeld"), GuideNames.tiers("Seinfeld"))
    }

    @Test
    fun keepsTheSubtitleWhenTheHeadIsTooShort() {
        assertEquals(listOf("W: The Story"), GuideNames.tiers("W: The Story"))
    }

    @Test
    fun picksTheFirstThatFits() {
        assertEquals("L&O: SVU", GuideNames.pick("Law & Order: Special Victims Unit") { it.length <= 10 })
        assertEquals("Law & Order: Special Victims Unit", GuideNames.pick("Law & Order: Special Victims Unit") { true })
    }

    @Test
    fun theShortestWhenNothingFits() {
        assertEquals("L&O", GuideNames.pick("Law & Order: Special Victims Unit") { false })
    }

    @Test
    fun localNewsLosesItsTime() {
        assertEquals(listOf("KOCO 5 News at 6am Saturday", "KOCO 5 News"), GuideNames.tiers("KOCO 5 News at 6am Saturday"))
        assertEquals("News 9 Saturday Morning", GuideNames.tiers("News 9 Saturday Morning").last())
    }
}
