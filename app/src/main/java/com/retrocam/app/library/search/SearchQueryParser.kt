package com.retrocam.app.library.search

import java.util.Calendar
import java.util.Locale

data class ParsedSearchCriteria(
    val rawQuery: String,
    val textTerms: List<String> = emptyList(),
    val isVideoFilter: Boolean? = null,
    val isFavoriteFilter: Boolean? = null,
    val isEditedFilter: Boolean? = null,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val isoFilter: Int? = null,
    val albumFilter: String? = null
)

object SearchQueryParser {

    private val MONTHS = mapOf(
        "january" to 0, "jan" to 0,
        "february" to 1, "feb" to 1,
        "march" to 2, "mar" to 2,
        "april" to 3, "apr" to 3,
        "may" to 4,
        "june" to 5, "jun" to 5,
        "july" to 6, "jul" to 6,
        "august" to 7, "aug" to 7,
        "september" to 8, "sep" to 8, "sept" to 8,
        "october" to 9, "oct" to 9,
        "november" to 10, "nov" to 10,
        "december" to 11, "dec" to 11
    )

    fun parse(query: String, nowMs: Long = System.currentTimeMillis()): ParsedSearchCriteria {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return ParsedSearchCriteria(rawQuery = "")
        }

        val tokens = trimmed.lowercase(Locale.US).split(Regex("\\s+"))
        val remainingText = mutableListOf<String>()

        var isVideoFilter: Boolean? = null
        var isFavoriteFilter: Boolean? = null
        var isEditedFilter: Boolean? = null
        var startTimestamp: Long? = null
        var endTimestamp: Long? = null
        var isoFilter: Int? = null

        val calNow = Calendar.getInstance().apply { timeInMillis = nowMs }
        val currentYear = calNow.get(Calendar.YEAR)

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            when {
                token in listOf("video", "videos", "movie", "movies", "clip", "clips") -> {
                    isVideoFilter = true
                }
                token in listOf("photo", "photos", "picture", "pictures", "image", "images", "shot", "shots") -> {
                    isVideoFilter = false
                }
                token in listOf("favorite", "favorites", "fav", "favs", "starred") -> {
                    isFavoriteFilter = true
                }
                token in listOf("edited", "remastered", "remaster", "edit") -> {
                    isEditedFilter = true
                }
                token == "today" -> {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startTimestamp = cal.timeInMillis
                    endTimestamp = startTimestamp + 86_400_000L - 1L
                }
                token == "yesterday" -> {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        add(Calendar.DAY_OF_YEAR, -1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startTimestamp = cal.timeInMillis
                    endTimestamp = startTimestamp + 86_400_000L - 1L
                }
                token == "iso" && i + 1 < tokens.size && tokens[i + 1].toIntOrNull() != null -> {
                    isoFilter = tokens[i + 1].toIntOrNull()
                    i++ // Skip next token
                }
                token.startsWith("iso") && token.removePrefix("iso").toIntOrNull() != null -> {
                    isoFilter = token.removePrefix("iso").toIntOrNull()
                }
                MONTHS.containsKey(token) -> {
                    val monthIdx = MONTHS[token]!!
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, currentYear)
                        set(Calendar.MONTH, monthIdx)
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startTimestamp = cal.timeInMillis
                    cal.add(Calendar.MONTH, 1)
                    endTimestamp = cal.timeInMillis - 1L
                }
                token.length == 4 && token.toIntOrNull() != null && token.toInt() in 1990..2100 -> {
                    val year = token.toInt()
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, Calendar.JANUARY)
                        set(Calendar.DAY_OF_MONTH, 1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startTimestamp = cal.timeInMillis
                    cal.add(Calendar.YEAR, 1)
                    endTimestamp = cal.timeInMillis - 1L
                }
                else -> {
                    remainingText.add(token)
                }
            }
            i++
        }

        return ParsedSearchCriteria(
            rawQuery = trimmed,
            textTerms = remainingText,
            isVideoFilter = isVideoFilter,
            isFavoriteFilter = isFavoriteFilter,
            isEditedFilter = isEditedFilter,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            isoFilter = isoFilter
        )
    }
}
