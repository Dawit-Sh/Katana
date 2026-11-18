package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.journal.JournalReadingEventType
import com.example.unifiedreader.journal.JournalSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One day of the reading card: how long was read, and how dark its tile is (0 none, 4 the most). */
data class ReadingCardDay(
    val date: LocalDate,
    val minutes: Int,
    val read: Boolean,
    val level: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

/**
 * The side menu's reading card: the last [weeks] weeks as a heat map (columns of Monday to
 * Sunday, the last one this week), the streak, the days read and the books finished in them, the
 * best day, and the last seven days.
 */
data class ReadingCardModel(
    val weeks: List<List<ReadingCardDay>>,
    /** The column a month's name stands over, and the name ("Aug"). */
    val monthLabels: List<Pair<Int, String>>,
    val firstDay: LocalDate,
    val today: LocalDate,
    val streak: Int,
    val daysRead: Int,
    val finished: Int,
    val bestDay: ReadingCardDay?,
    val lastSeven: List<ReadingCardDay>,
    val weekMinutes: Int,
) {
    val averageMinutes: Int get() = (weekMinutes + LAST_DAYS / 2) / LAST_DAYS

    companion object {
        const val WEEKS = 15
        const val LAST_DAYS = 7
    }
}

/** A day's reading as the card needs it: minutes read, and whether the journal saw any reading at all. */
data class ReadingCardInput(val minutes: Int, val read: Boolean)

/**
 * Builds the card from the journal's days ([days], by date) and the dates books were finished on
 * ([finishedOn]). A day with reading but under a minute still counts as read, at the lightest tile.
 */
fun readingCardModel(
    days: Map<LocalDate, ReadingCardInput>,
    finishedOn: List<LocalDate>,
    today: LocalDate,
    weeks: Int = ReadingCardModel.WEEKS,
): ReadingCardModel {
    val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstDay = lastMonday.minusWeeks((weeks - 1).toLong())
    fun day(date: LocalDate): ReadingCardDay {
        val input = days[date]
        val future = date.isAfter(today)
        val read = !future && input?.read == true
        val minutes = if (future) 0 else input?.minutes ?: 0
        return ReadingCardDay(
            date = date,
            minutes = minutes,
            read = read,
            level = if (read) readingLevel(minutes) else 0,
            isToday = date == today,
            isFuture = future,
        )
    }
    val columns = (0 until weeks).map { week ->
        (0 until DAYS_IN_WEEK).map { weekday -> day(firstDay.plusDays((week * DAYS_IN_WEEK + weekday).toLong())) }
    }
    val shown = columns.flatten().filterNot(ReadingCardDay::isFuture)
    val readDates = shown.filter(ReadingCardDay::read).map(ReadingCardDay::date).toSet()
    // A streak still counts while today has no reading yet: it is broken only by a whole day missed.
    var cursor = if (today in readDates) today else today.minusDays(1)
    var streak = 0
    while (cursor in readDates || (cursor.isBefore(firstDay) && days[cursor]?.read == true)) {
        streak++
        cursor = cursor.minusDays(1)
    }
    val lastSeven = (ReadingCardModel.LAST_DAYS - 1 downTo 0).map { back -> day(today.minusDays(back.toLong())) }
    return ReadingCardModel(
        weeks = columns,
        monthLabels = monthLabels(columns),
        firstDay = firstDay,
        today = today,
        streak = streak,
        daysRead = readDates.size,
        finished = finishedOn.count { date -> !date.isBefore(firstDay) && !date.isAfter(today) },
        bestDay = shown.filter { it.minutes > 0 }.maxWithOrNull(compareBy<ReadingCardDay> { it.minutes }.thenBy { it.date }),
        lastSeven = lastSeven,
        weekMinutes = lastSeven.sumOf(ReadingCardDay::minutes),
    )
}

/** How dark a read day's tile is, by minutes: up to 20, 40, 70, then more. */
fun readingLevel(minutes: Int): Int = 1 + LevelCeilings.count { ceiling -> minutes > ceiling }

/** A month's name over the column holding its first day (the first column takes its own month), never two too close. */
private fun monthLabels(columns: List<List<ReadingCardDay>>): List<Pair<Int, String>> {
    val labels = mutableListOf<Pair<Int, String>>()
    columns.forEachIndexed { index, week ->
        val first = week.firstOrNull { it.date.dayOfMonth == 1 }?.date ?: week.first().date.takeIf { index == 0 }
        val label = first?.let { index to it.month.getDisplayName(TextStyle.SHORT, MonthNames) } ?: return@forEachIndexed
        val previous = labels.lastOrNull()?.first
        when {
            previous == null || index - previous >= MIN_LABEL_COLUMNS -> labels += label
            // The first column's month is nearly over: the new month's name takes its place.
            previous == 0 -> labels[labels.lastIndex] = label
        }
    }
    return labels
}

/** The journal's reading per day and its finishing dates, by the device's calendar. */
fun readingCardModel(
    snapshot: JournalSnapshot,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): ReadingCardModel {
    val start = today.minusWeeks(ReadingCardModel.WEEKS.toLong() + 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val events = snapshot.events.filter { event -> event.occurredAtEpochMillis in start until end }
    val sessions = snapshot.sessions.filter { session -> session.endedAtEpochMillis >= start && session.startedAtEpochMillis < end }
    val days = computeActivityDays(events, sessions, periodStart = start, periodEnd = end).associate { day ->
        LocalDate.of(day.year, day.month + 1, day.dayOfMonth) to ReadingCardInput(
            minutes = ((day.readingMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS).toInt(),
            read = day.eventCount > 0,
        )
    }
    val finishedOn = events
        .filter { event -> event.eventType == JournalReadingEventType.FINISHED }
        .distinctBy { event -> event.itemId }
        .map { event -> Instant.ofEpochMilli(event.occurredAtEpochMillis).atZone(zone).toLocalDate() }
    return readingCardModel(days = days, finishedOn = finishedOn, today = today)
}

/** Three-letter months ("Sep"): the JDK's UK names now say "Sept", Android 9's don't. */
private val MonthNames: Locale = Locale.US
private const val DAYS_IN_WEEK = 7
private const val MIN_LABEL_COLUMNS = 3
/** The most minutes of each lighter tile: up to 20, 40 and 70; more is the darkest. */
private val LevelCeilings = listOf(20, 40, 70)
private const val MINUTE_MILLIS = 60_000L
