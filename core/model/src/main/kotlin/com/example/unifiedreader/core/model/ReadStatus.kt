package com.example.unifiedreader.core.model

/**
 * A book's reading status, as Grimmory keeps it (and Katana Desk, which answers Grimmory's API for its own
 * books): [serverName] is Grimmory's word for it, [label] the app's. Read and Unread are the book's place
 * too (finished, or back at the start); the others are a word kept with the book.
 */
enum class ReadStatus(val serverName: String, val label: String) {
    UNREAD("UNREAD", "Unread"),
    READING("READING", "Reading"),
    RE_READING("RE_READING", "Re-reading"),
    READ("READ", "Read"),
    PARTIALLY_READ("PARTIALLY_READ", "Partially read"),
    PAUSED("PAUSED", "Paused"),
    WONT_READ("WONT_READ", "Won't read"),
    ABANDONED("ABANDONED", "Abandoned"),
    ;

    /** Read finishes the book and Unread clears its place: both go through Mark finished and Mark unread. */
    val movesPlace: Boolean
        get() = this == READ || this == UNREAD

    companion object {
        /** A server's status, or null for none, Grimmory's UNSET, or one this version doesn't know. */
        fun fromServer(value: String?): ReadStatus? {
            val name = value?.trim()?.takeIf(String::isNotEmpty)
            return entries.firstOrNull { status -> status.serverName.equals(name, ignoreCase = true) }
        }
    }
}

/**
 * A status kept on this device for one book: set here or taken from the book's server at [setAtEpochMillis],
 * and [unsent] while it waits to go to that server (a book that syncs nowhere keeps it waiting, harmlessly).
 */
data class ReadStatusRecord(
    val status: ReadStatus,
    val setAtEpochMillis: Long,
    val unsent: Boolean = false,
) {
    /** How the device keeps it: "PAUSED|1763712000000|1". */
    fun encode(): String = "${status.name}|$setAtEpochMillis|${if (unsent) 1 else 0}"

    companion object {
        /** A kept record, or null for anything else (a value this version can't read is dropped, not guessed). */
        fun decode(value: String?): ReadStatusRecord? {
            val parts = value?.split('|')?.takeIf { it.size == RECORD_PARTS }
            val status = parts?.let { ReadStatus.entries.firstOrNull { status -> status.name == it[0] } }
            val setAt = parts?.get(1)?.toLongOrNull()
            val unsent = when (parts?.get(2)) {
                "1" -> true
                "0" -> false
                else -> null
            }
            return if (status != null && setAt != null && unsent != null) ReadStatusRecord(status, setAt, unsent) else null
        }

        private const val RECORD_PARTS = 3
    }
}

/**
 * The statuses this book's page offers: all eight for a book, without Read and Unread where its source can't
 * be marked finished or unread (Stump); none for a series (Kavita's and Stump's series screens), which isn't a book.
 */
fun ReadableItem.readStatusChoices(): List<ReadStatus> = when {
    isKavitaSeriesContainer() || isStumpSeriesContainer() -> emptyList()
    supportsManualReadStatusAction() -> ReadStatus.entries
    else -> ReadStatus.entries.filterNot(ReadStatus::movesPlace)
}

/** What the status rules need of a book's place here: some of it read ([hasPlace]), or read to the end ([isFinished]). */
data class ReadStatusPlace(
    val hasPlace: Boolean,
    val isFinished: Boolean,
)

/** The status a book shows: its own, or for a book never given one, what its place says. */
fun shownReadStatus(record: ReadStatusRecord?, place: ReadStatusPlace?): ReadStatus = record?.status ?: placeReadStatus(place)

/** A book's status from its place alone: Read when finished, Reading once started, else Unread. */
fun placeReadStatus(place: ReadStatusPlace?): ReadStatus = when {
    place?.isFinished == true -> ReadStatus.READ
    place?.hasPlace == true -> ReadStatus.READING
    else -> ReadStatus.UNREAD
}

/**
 * The status after reading here moved a book's place from [before] to [after], or null when it stays, as
 * Katana Desk does: reaching the end makes any status Read; reading an Unread book makes it Reading, and so
 * does going back into a Read one. Re-reading, Paused, Abandoned and the rest stay until the book is
 * finished. A place saved again at the end (a finished book opened) is no new finish, and a cleared place
 * is Unread's own doing, not reading.
 */
fun readStatusAfterReading(status: ReadStatus, before: ReadStatusPlace?, after: ReadStatusPlace?): ReadStatus? = when {
    after == null -> null
    after.isFinished -> ReadStatus.READ.takeIf { status != ReadStatus.READ && before?.isFinished != true }
    !after.hasPlace -> null
    status == ReadStatus.UNREAD -> ReadStatus.READING
    status == ReadStatus.READ && (before == null || !before.hasPlace || before.isFinished) -> ReadStatus.READING
    else -> null
}

/** What a sync does with the status a book's server has. */
sealed interface ServerReadStatusStep {
    /** Nothing changes here and nothing goes up. */
    data object Keep : ServerReadStatusStep

    /** This device's status waits to go up and goes now. */
    data object Send : ServerReadStatusStep

    /** The server's status is the one to keep: [record] replaces this device's. */
    data class Take(val record: ReadStatusRecord) : ServerReadStatusStep

    /** The server already has this device's waiting status: [record] is it, no longer waiting. */
    data class Settled(val record: ReadStatusRecord) : ServerReadStatusStep
}

/**
 * A book's status on its server, as a sync reads it: [status] (null when the server doesn't say), set at
 * [setAtEpochMillis] when it tells.
 */
data class ServerReadStatus(
    val status: ReadStatus?,
    val setAtEpochMillis: Long? = null,
)

/**
 * What a sync does with a book's status on its [server]. The most recent change wins, as with places:
 * - A status set here and not yet sent goes up, unless the server's was set clearly later (by more than
 *   [READ_STATUS_CLOCK_TOLERANCE_MILLIS]; a server that doesn't say when can't be clearly later).
 * - A status in step with the server takes the server's when it changed there.
 * - A book with no status of its own takes the server's only when it differs from what its place shows
 *   ([statusFromPlace]), so a library of books that agree keeps nothing.
 * - While this device's place for the book waits to go up ([localPlaceUnsent]), the server's status is from
 *   before it (its server makes Reading or Read of a place itself): it is left until the next sync.
 */
fun decideServerReadStatus(
    local: ReadStatusRecord?,
    statusFromPlace: ReadStatus,
    server: ServerReadStatus,
    localPlaceUnsent: Boolean,
    nowEpochMillis: Long,
): ServerReadStatusStep {
    val status = server.status
    val serverAt = server.setAtEpochMillis?.trustedAt(nowEpochMillis)
    // Kept with the server's time, but never ahead of this device's clock: reading here after it is reading after it.
    val taken = status?.let { ServerReadStatusStep.Take(ReadStatusRecord(it, serverAt?.coerceAtMost(nowEpochMillis) ?: nowEpochMillis)) }
    return when {
        status == null || taken == null -> if (local?.unsent == true) ServerReadStatusStep.Send else ServerReadStatusStep.Keep
        local == null -> if (localPlaceUnsent || status == statusFromPlace) ServerReadStatusStep.Keep else taken
        local.unsent -> waitingStatusAgainst(local, status, serverAt, taken, nowEpochMillis)
        status == local.status || localPlaceUnsent -> ServerReadStatusStep.Keep
        else -> taken
    }
}

// A status waiting here against the server's: the same is in step, one set clearly later wins, else this one goes up.
private fun waitingStatusAgainst(
    local: ReadStatusRecord,
    server: ReadStatus,
    serverAt: Long?,
    taken: ServerReadStatusStep.Take,
    nowEpochMillis: Long,
): ServerReadStatusStep = when {
    server == local.status -> ServerReadStatusStep.Settled(local.copy(unsent = false))
    serverAt != null && serverAt > local.comparableSetAt(nowEpochMillis) + READ_STATUS_CLOCK_TOLERANCE_MILLIS -> taken
    else -> ServerReadStatusStep.Send
}

// A waiting status stamped more than a day ahead of this device's clock (set while the clock was wrong) counts as now.
private fun ReadStatusRecord.comparableSetAt(nowEpochMillis: Long): Long = setAtEpochMillis.clampedToNowIfBeyondLimit(nowEpochMillis)

/**
 * How much later a server's status has to be set than one waiting here to replace it: the margin places use
 * (`PENDING_LOCAL_PROGRESS_CLOCK_TOLERANCE_MILLIS`), for the same reason (the server stamps with its own clock).
 */
const val READ_STATUS_CLOCK_TOLERANCE_MILLIS: Long = 2 * 60 * 1000L
