package com.example.unifiedreader.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Times of day as this device shows them: "9:11 PM", or "21:11" when Android's clock is set to 24-hour.
 * The app sets [uses24Hour] from that setting when it starts and whenever it comes back to the front;
 * until then it reads the 12-hour way.
 */
object DeviceClock {
    @Volatile
    var uses24Hour: Boolean = false

    private val TwelveHour: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TwentyFourHour: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** [time] needs an hour and a minute: a LocalTime, LocalDateTime or ZonedDateTime. */
    fun format(time: TemporalAccessor): String = (if (uses24Hour) TwentyFourHour else TwelveHour).format(time)

    fun format(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
