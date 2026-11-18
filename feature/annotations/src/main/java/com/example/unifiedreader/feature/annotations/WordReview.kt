package com.example.unifiedreader.feature.annotations

/** A word's review state after an answer: its level and when it is next due. */
data class WordReviewStep(val level: Int, val dueAtEpochMillis: Long)

/**
 * When a saved word comes back as a flashcard. Known moves it one level up and away for longer
 * (a day, then 3, 7, 14 and 30 days); Again puts it back to the start, due at once.
 */
object WordReviewSchedule {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    private val IntervalDays = longArrayOf(1L, 3L, 7L, 14L, 30L)

    val maxLevel: Int get() = IntervalDays.size

    fun next(level: Int, known: Boolean, atEpochMillis: Long): WordReviewStep {
        if (!known) return WordReviewStep(level = 0, dueAtEpochMillis = atEpochMillis)
        val nextLevel = (level.coerceAtLeast(0) + 1).coerceAtMost(maxLevel)
        return WordReviewStep(level = nextLevel, dueAtEpochMillis = atEpochMillis + IntervalDays[nextLevel - 1] * DAY_MILLIS)
    }

    /** Whether a word is up for review at [atEpochMillis]: never reviewed, or its day has come. */
    fun isDue(word: SavedWord, atEpochMillis: Long): Boolean =
        word.reviewDueAtEpochMillis?.let { due -> due <= atEpochMillis } ?: true

    /** The words to review now, those waiting longest first; [all] takes every word, due or not. */
    fun round(words: List<SavedWord>, atEpochMillis: Long, all: Boolean = false): List<SavedWord> =
        words
            .filter { word -> all || isDue(word, atEpochMillis) }
            .sortedWith(
                compareBy<SavedWord>(
                    { it.reviewDueAtEpochMillis != null },
                    { it.reviewDueAtEpochMillis },
                    { it.createdAtEpochMillis },
                ),
            )
}

/**
 * One round of flashcards: the words in turn; I know it takes a word out of the round, Again sends
 * it to the back so it comes round again later in the same round.
 */
data class WordReviewRound(
    val queue: List<String>,
    val total: Int,
    val known: Int = 0,
) {
    val current: String? get() = queue.firstOrNull()

    val isFinished: Boolean get() = queue.isEmpty()

    /** The place of the current card, counting from 1, for "3 of 12". */
    val position: Int get() = (known + 1).coerceAtMost(total)

    fun answer(known: Boolean): WordReviewRound {
        val head = current ?: return this
        return if (known) {
            copy(queue = queue.drop(1), known = this.known + 1)
        } else {
            copy(queue = queue.drop(1) + head)
        }
    }

    companion object {
        fun of(words: List<SavedWord>): WordReviewRound = WordReviewRound(queue = words.map(SavedWord::id), total = words.size)
    }
}
