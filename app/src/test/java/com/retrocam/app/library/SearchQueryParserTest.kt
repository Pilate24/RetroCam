package com.retrocam.app.library

import com.retrocam.app.library.search.SearchQueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SearchQueryParserTest {

    @Test
    fun testParseVideoFilter() {
        val parsed = SearchQueryParser.parse("videos from beach")
        assertEquals(true, parsed.isVideoFilter)
        assertTrue(parsed.textTerms.contains("from"))
        assertTrue(parsed.textTerms.contains("beach"))
    }

    @Test
    fun testParsePhotoFilter() {
        val parsed = SearchQueryParser.parse("photos sunset")
        assertEquals(false, parsed.isVideoFilter)
        assertTrue(parsed.textTerms.contains("sunset"))
    }

    @Test
    fun testParseFavoritesAndEdited() {
        val parsedFav = SearchQueryParser.parse("favorite portraits")
        assertEquals(true, parsedFav.isFavoriteFilter)
        assertTrue(parsedFav.textTerms.contains("portraits"))

        val parsedEdit = SearchQueryParser.parse("edited landscape")
        assertEquals(true, parsedEdit.isEditedFilter)
        assertTrue(parsedEdit.textTerms.contains("landscape"))
    }

    @Test
    fun testParseIso() {
        val parsed1 = SearchQueryParser.parse("iso 800")
        assertEquals(800, parsed1.isoFilter)

        val parsed2 = SearchQueryParser.parse("iso400 street")
        assertEquals(400, parsed2.isoFilter)
        assertTrue(parsed2.textTerms.contains("street"))
    }

    @Test
    fun testParseDateKeywords() {
        val now = 1700000000000L // arbitrary fixed time
        val parsedToday = SearchQueryParser.parse("today", nowMs = now)
        assertNotNull(parsedToday.startTimestamp)
        assertNotNull(parsedToday.endTimestamp)
        assertTrue(parsedToday.endTimestamp!! > parsedToday.startTimestamp!!)

        val parsedYesterday = SearchQueryParser.parse("yesterday", nowMs = now)
        assertNotNull(parsedYesterday.startTimestamp)
        assertNotNull(parsedYesterday.endTimestamp)
        assertTrue(parsedYesterday.endTimestamp!! < parsedToday.startTimestamp!!)
    }

    @Test
    fun testParseMonthAndYear() {
        val parsedMonth = SearchQueryParser.parse("October")
        assertNotNull(parsedMonth.startTimestamp)
        assertNotNull(parsedMonth.endTimestamp)

        val parsedYear = SearchQueryParser.parse("2024")
        assertNotNull(parsedYear.startTimestamp)
        assertNotNull(parsedYear.endTimestamp)
        val cal = Calendar.getInstance().apply { timeInMillis = parsedYear.startTimestamp!! }
        assertEquals(2024, cal.get(Calendar.YEAR))
    }

    @Test
    fun testEmptyQuery() {
        val parsed = SearchQueryParser.parse("")
        assertEquals("", parsed.rawQuery)
        assertTrue(parsed.textTerms.isEmpty())
        assertNull(parsed.isVideoFilter)
    }
}
