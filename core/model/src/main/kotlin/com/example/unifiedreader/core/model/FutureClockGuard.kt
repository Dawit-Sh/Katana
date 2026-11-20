package com.example.unifiedreader.core.model

/**
 * How far ahead of this device's clock a time may be and still count: a day. A server or device whose clock
 * is wrong by more stamps its changes days or years ahead, and such a time would win every "the most recent
 * wins" comparison until that day came. A day leaves room for time zones and for a clock a little off.
 */
const val FUTURE_CLOCK_LIMIT_MILLIS: Long = 24 * 60 * 60 * 1000L

/** Whether [this] time is further ahead of [nowEpochMillis] than [FUTURE_CLOCK_LIMIT_MILLIS]. */
fun Long.isBeyondFutureClockLimit(nowEpochMillis: Long): Boolean = this - nowEpochMillis > FUTURE_CLOCK_LIMIT_MILLIS

/** [this] time, or null when it is too far ahead of [nowEpochMillis] to go by ([isBeyondFutureClockLimit]). */
fun Long.trustedAt(nowEpochMillis: Long): Long? = takeUnless { it.isBeyondFutureClockLimit(nowEpochMillis) }

/** [this] time, or [nowEpochMillis] when it is too far ahead to go by. */
fun Long.clampedToNowIfBeyondLimit(nowEpochMillis: Long): Long =
    if (isBeyondFutureClockLimit(nowEpochMillis)) nowEpochMillis else this
