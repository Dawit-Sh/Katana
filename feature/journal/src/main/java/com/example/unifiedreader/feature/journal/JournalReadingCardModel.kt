package com.example.unifiedreader.feature.journal

import com.example.unifiedreader.journal.JournalSnapshot
import com.example.unifiedreader.journal.ReadingGoal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One day of the side menu's heat map: how long was read, and its step against the daily goal (0 none to 4). */
data class ReadingCardDay(
    val date: LocalDate,
    val minutes: Int,
    val step: Int,
    val isToday: Boolean,
    val isFuture: Boolean,
)

/**
 * The side menu's Reading card: today's minutes, and the last [WEEKS] weeks as a heat map, a column a
 * week from Monday at the top to Sunday, this week last, each day stepped against the daily goal.
 */
data class ReadingCardModel(
    val weeks: List<List<ReadingCardDay>>,
    val today: LocalDate,
    val todayMinutes: Int,
) {
    companion object {
        const val WEEKS = 16
    }
}

/** A day's reading as the card needs it: minutes read, and whether the journal saw any reading at all. */
data class ReadingCardInput(val minutes: Int, val read: Boolean)

/**
 * Builds the card from the journal's days ([days], by date) against [goalMinutes]. A day with reading
 * but under a minute counted is still a reading day, at the first step.
 */
fun readingCardModel(
    days: Map<LocalDate, ReadingCardInput>,
    today: LocalDate,
    goalMinutes: Int,
    weeks: Int = ReadingCardModel.WEEKS,
): ReadingCardModel {
    val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstDay = lastMonday.minusWeeks((weeks - 1).toLong())
    fun day(date: LocalDate): ReadingCardDay {
        val input = days[date]
        val future = date.isAfter(today)
        val minutes = if (future) 0 else input?.minutes ?: 0
        return ReadingCardDay(
            date = date,
            minutes = minutes,
            step = if (future) 0 else ReadingGoal.step(minutes, goalMinutes, read = input?.read == true),
            isToday = date == today,
            isFuture = future,
        )
    }
    val columns = (0 until weeks).map { week ->
        (0 until DAYS_IN_WEEK).map { weekday -> day(firstDay.plusDays((week * DAYS_IN_WEEK + weekday).toLong())) }
    }
    return ReadingCardModel(weeks = columns, today = today, todayMinutes = days[today]?.minutes ?: 0)
}

/**
 * The months' names over [columns] (weeks, Monday first): over the column holding a month's first day,
 * the first column taking its own month, never two closer than three columns.
 */
fun readingCardMonthLabels(columns: List<List<ReadingCardDay>>): List<Pair<Int, String>> {
    val labels = mutableListOf<Pair<Int, String>>()
    columns.forEachIndexed { index, week ->
        val first = week.firstOrNull { it.date.dayOfMonth == 1 }?.date ?: week.firstOrNull()?.date?.takeIf { index == 0 }
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

/** The journal's reading per day, by the device's calendar, against [goalMinutes]. */
fun readingCardModel(
    snapshot: JournalSnapshot,
    today: LocalDate,
    goalMinutes: Int,
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
    return readingCardModel(days = days, today = today, goalMinutes = goalMinutes)
}

/** What the card's first line says before "read today": "1 h 12", "2 h", "22 min", "0 min". */
fun readingTodayTime(minutes: Int): String {
    val shown = minutes.coerceAtLeast(0)
    if (shown < MINUTES_PER_HOUR) return "$shown min"
    val rest = shown % MINUTES_PER_HOUR
    return if (rest == 0) "${shown / MINUTES_PER_HOUR} h" else "${shown / MINUTES_PER_HOUR} h ${rest.toString().padStart(2, '0')}"
}

/** Three-letter months ("Sep"): the JDK's UK names now say "Sept", Android 9's don't. */
private val MonthNames: Locale = Locale.US
private const val DAYS_IN_WEEK = 7
private const val MIN_LABEL_COLUMNS = 3
private const val MINUTES_PER_HOUR = 60
private const val MINUTE_MILLIS = 60_000L
