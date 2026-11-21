package com.example.unifiedreader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.unifiedreader.feature.annotations.KeptWord
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The side menu's word of the day as kept for its day ([KeptWord]: the date and the word), so the word
 * stays all day, and after "another word", even as words are saved or removed. A small preferences file.
 */
class WordOfTheDayStore internal constructor(
    // Null keeps nothing: tests that build the screen without Android.
    openPreferences: () -> SharedPreferences?,
) {
    constructor(context: Context) : this({ context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) })

    private val preferences: SharedPreferences? by lazy(openPreferences)

    fun read(): KeptWord? {
        val date = preferences?.getString(DATE_KEY, null)?.let(::parseDate)
        val wordId = preferences?.getString(WORD_KEY, null)?.takeIf(String::isNotEmpty)
        return if (date != null && wordId != null) KeptWord(date = date, wordId = wordId) else null
    }

    fun write(kept: KeptWord) {
        preferences?.edit {
            putString(DATE_KEY, kept.date.toString())
            putString(WORD_KEY, kept.wordId)
        }
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val PREFERENCES = "word_of_the_day"
        const val DATE_KEY = "date"
        const val WORD_KEY = "word_id"
    }
}
