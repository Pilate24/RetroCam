package com.retrocam.app.library

import com.retrocam.app.library.model.DateGroupingHelper
import com.retrocam.app.library.model.LibraryListItem
import com.retrocam.app.library.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateGroupingTest {

    @Test
    fun testGetSectionTitleTodayAndYesterday() {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        assertEquals("Today", DateGroupingHelper.getSectionTitle(now, now))

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.timeInMillis
        assertEquals("Yesterday", DateGroupingHelper.getSectionTitle(yesterday, now))
    }

    @Test
    fun testGroupMediaItems() {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis

        val itemToday = MediaItem(
            id = "1",
            displayName = "today.jpg",
            dateTaken = now
        )

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val itemYesterday = MediaItem(
            id = "2",
            displayName = "yesterday.jpg",
            dateTaken = cal.timeInMillis
        )

        val grouped = DateGroupingHelper.groupMediaItems(listOf(itemToday, itemYesterday), now)

        assertEquals(4, grouped.size)
        assertTrue(grouped[0] is LibraryListItem.Header)
        assertEquals("Today", (grouped[0] as LibraryListItem.Header).title)
        assertTrue(grouped[1] is LibraryListItem.Media)
        assertTrue(grouped[2] is LibraryListItem.Header)
        assertEquals("Yesterday", (grouped[2] as LibraryListItem.Header).title)
        assertTrue(grouped[3] is LibraryListItem.Media)
    }
}
