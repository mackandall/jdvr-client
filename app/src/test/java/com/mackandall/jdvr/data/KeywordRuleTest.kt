package com.mackandall.jdvr.data

import com.mackandall.jdvr.data.NextPvrBackend.Companion.keywordOf
import com.mackandall.jdvr.data.NextPvrBackend.Companion.keywordQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordRuleTest {
    @Test
    fun writesWhatNextPvrsWebPageWrites() {
        assertEquals("title like '%Jeopardy%'", keywordQuery(" Jeopardy ", KeywordMatch.TITLE))
        assertEquals("(title like '%Jeopardy%' or description like '%Jeopardy%')", keywordQuery("Jeopardy", KeywordMatch.ANYWHERE))
        assertEquals("(cast_member like '%Hitchcock%' or crew like '%Hitchcock%')", keywordQuery("Hitchcock", KeywordMatch.PEOPLE))
    }

    @Test
    fun quoteIsDoubled() {
        assertEquals("title like '%Bob's Burgers%'".replace("'s", "''s"), keywordQuery("Bob's Burgers", KeywordMatch.TITLE))
    }

    @Test
    fun readsItsOwnRulesBack() {
        for (m in KeywordMatch.entries) {
            for (k in listOf("Jeopardy", "Bob's Burgers", "100%")) {
                assertEquals(k to m, keywordOf(keywordQuery(k, m)))
            }
        }
    }

    @Test
    fun readsKodisKeywordRules() {
        assertEquals("Jeopardy" to KeywordMatch.ANYWHERE, keywordOf("KEYWORD: Jeopardy"))
    }

    @Test
    fun otherAdvancedRulesAreLeftAlone() {
        assertNull(keywordOf("title like 'Jeopardy%' and channel_oid=7162"))
        assertNull(keywordOf("genre like '%Sports%'"))
        assertNull(keywordOf(""))
    }
}
