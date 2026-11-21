package com.example.unifiedreader.feature.annotations

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The side menu's word of the day: a word from My words picked at random, the same all day. The day's
 * order is a shuffle of every word seeded by the date; the first is the day's word, and "another word"
 * steps to the next in that order (round to the first after the last), which then stays for the day.
 */
object WordOfTheDay {
    /**
     * Today's word among [words]: the one [kept] for [date] if it is still saved, else the first of the
     * date's shuffle. Null when there are no words.
     */
    fun pick(words: List<SavedWord>, date: LocalDate, kept: KeptWord? = null): SavedWord? {
        val stillKept = kept?.takeIf { it.date == date }?.let { keptWord -> words.firstOrNull { it.id == keptWord.wordId } }
        return stillKept ?: order(words, date).firstOrNull()
    }

    /** The word after [current] in [date]'s order, for "another word"; [current] itself when it is the only one. */
    fun next(words: List<SavedWord>, date: LocalDate, current: SavedWord?): SavedWord? {
        if (words.isEmpty()) return null
        val order = order(words, date)
        val at = order.indexOfFirst { it.id == current?.id }
        return order[(at + 1).mod(order.size)]
    }

    /** Every word in [date]'s order: sorted by id first, so the same words shuffle the same way on any device. */
    fun order(words: List<SavedWord>, date: LocalDate): List<SavedWord> =
        words.sortedBy(SavedWord::id).shuffled(Random(date.toEpochDay() * SEED_STEP + SEED_SALT))

    private const val SEED_STEP = 31L
    private const val SEED_SALT = 7L
}

/** The word kept as [date]'s word of the day (after "another word", or once it was first shown). */
data class KeptWord(val date: LocalDate, val wordId: String)

/** How far a saved word has come in its reviews: never reviewed, being learned, or learned (the longest wait). */
enum class WordProgress { New, Learning, Learned }

fun SavedWord.progress(): WordProgress = when {
    reviewedAtEpochMillis == null -> WordProgress.New
    reviewLevel >= WordReviewSchedule.maxLevel -> WordProgress.Learned
    else -> WordProgress.Learning
}

/** Whether this word was answered "I know it" on [date]: reviewed that day and up a level. */
fun SavedWord.knownOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val at = reviewedAtEpochMillis ?: return false
    return reviewLevel > 0 && Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == date
}
