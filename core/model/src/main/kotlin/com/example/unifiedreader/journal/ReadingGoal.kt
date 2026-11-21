package com.example.unifiedreader.journal

/**
 * The daily reading goal and the one scale of reading days it sets, for the side menu's heat map and
 * the Journal's calendars alike: five steps, no reading, under half the goal, under the goal, the goal
 * met, twice the goal or more. Katana Desk colours its days the same way.
 */
object ReadingGoal {
    /** Minutes a day, until one is picked in Settings › Reading or the Journal. */
    const val DEFAULT_MINUTES: Int = 30

    /** The goals offered, in minutes a day. */
    val Choices: List<Int> = listOf(10, 15, 20, 30, 45, 60, 90, 120)

    /** No reading, then the four steps. */
    const val STEPS: Int = 5

    private const val MIN_MINUTES = 5
    private const val MAX_MINUTES = 600

    /** A stored goal kept within sense; anything else is the default. */
    fun coerce(minutes: Int?): Int = minutes?.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: DEFAULT_MINUTES

    /**
     * A day's step, 0 to 4, for [minutes] read against [goalMinutes]. A day [read] on with no minutes
     * counted (a place moved on another device) is the first step, as a day under half the goal.
     */
    fun step(minutes: Int, goalMinutes: Int, read: Boolean = minutes > 0): Int {
        val goal = coerce(goalMinutes)
        return when {
            minutes <= 0 -> if (read) UNDER_HALF else NO_READING
            minutes * 2 < goal -> UNDER_HALF
            minutes < goal -> UNDER_GOAL
            minutes < goal * 2 -> GOAL_MET
            else -> TWICE_THE_GOAL
        }
    }

    /** The steps by name. */
    const val NO_READING: Int = 0
    const val UNDER_HALF: Int = 1
    const val UNDER_GOAL: Int = 2
    const val GOAL_MET: Int = 3
    const val TWICE_THE_GOAL: Int = 4

    /** "30 min a day". */
    fun label(goalMinutes: Int): String = "${coerce(goalMinutes)} min a day"

    /** What each step means for [goalMinutes], from no reading up. */
    fun stepNames(goalMinutes: Int): List<String> {
        val goal = coerce(goalMinutes)
        return listOf(
            "No reading",
            "Under ${halfOf(goal)} min",
            "Under $goal min (your goal)",
            "Goal met",
            "Twice your goal (${goal * 2} min or more)",
        )
    }

    /** Half of a goal as it reads: "7.5" for 15, "15" for 30. */
    private fun halfOf(goal: Int): String = if (goal % 2 == 0) (goal / 2).toString() else "${goal / 2}.5"
}
