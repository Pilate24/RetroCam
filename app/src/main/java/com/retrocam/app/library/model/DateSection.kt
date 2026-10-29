package com.retrocam.app.library.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class LibraryListItem {
    data class Header(val title: String, val dateRangeFormatted: String? = null, val count: Int = 0) : LibraryListItem()
    data class Media(val item: MediaItem) : LibraryListItem()
}

object DateGroupingHelper {

    fun getSectionTitle(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return "Undated"

        val calNow = Calendar.getInstance().apply { timeInMillis = nowMs }
        val calItem = Calendar.getInstance().apply { timeInMillis = timestampMs }

        val yearNow = calNow.get(Calendar.YEAR)
        val yearItem = calItem.get(Calendar.YEAR)
        val dayOfYearNow = calNow.get(Calendar.DAY_OF_YEAR)
        val dayOfYearItem = calItem.get(Calendar.DAY_OF_YEAR)

        if (yearNow == yearItem) {
            val dayDiff = dayOfYearNow - dayOfYearItem
            when {
                dayDiff == 0 -> return "Today"
                dayDiff == 1 -> return "Yesterday"
                dayDiff in 2..6 -> return "Earlier this week"
                calNow.get(Calendar.MONTH) == calItem.get(Calendar.MONTH) -> return "Earlier this month"
                else -> {
                    val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                    return sdf.format(Date(timestampMs))
                }
            }
        } else {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            return sdf.format(Date(timestampMs))
        }
    }

    /**
     * Groups a sorted (newest first) list of media items into a list of [LibraryListItem]
     * interleaving section headers with the items.
     */
    fun groupMediaItems(items: List<MediaItem>, nowMs: Long = System.currentTimeMillis()): List<LibraryListItem> {
        val result = mutableListOf<LibraryListItem>()
        var currentSection: String? = null
        var sectionItems = mutableListOf<MediaItem>()

        for (item in items) {
            val title = getSectionTitle(if (item.dateTaken > 0) item.dateTaken else item.dateAdded, nowMs)
            if (title != currentSection) {
                if (currentSection != null && sectionItems.isNotEmpty()) {
                    result.add(LibraryListItem.Header(currentSection, count = sectionItems.size))
                    result.addAll(sectionItems.map { LibraryListItem.Media(it) })
                    sectionItems.clear()
                }
                currentSection = title
            }
            sectionItems.add(item)
        }

        if (currentSection != null && sectionItems.isNotEmpty()) {
            result.add(LibraryListItem.Header(currentSection, count = sectionItems.size))
            result.addAll(sectionItems.map { LibraryListItem.Media(it) })
        }

        return result
    }
}
