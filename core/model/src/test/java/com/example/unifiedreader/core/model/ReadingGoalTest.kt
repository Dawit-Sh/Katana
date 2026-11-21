package com.example.unifiedreader.core.model

import com.example.unifiedreader.journal.ReadingGoal
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingGoalTest {
    @Test
    fun `a day's step is set by the goal`() {
        val goal = 30
        assertEquals(0, ReadingGoal.step(minutes = 0, goalMinutes = goal))
        assertEquals(1, ReadingGoal.step(minutes = 1, goalMinutes = goal))
        assertEquals(1, ReadingGoal.step(minutes = 14, goalMinutes = goal))
        assertEquals(2, ReadingGoal.step(minutes = 15, goalMinutes = goal))
        assertEquals(2, ReadingGoal.step(minutes = 29, goalMinutes = goal))
        assertEquals(3, ReadingGoal.step(minutes = 30, goalMinutes = goal))
        assertEquals(3, ReadingGoal.step(minutes = 59, goalMinutes = goal))
        assertEquals(4, ReadingGoal.step(minutes = 60, goalMinutes = goal))
        assertEquals(4, ReadingGoal.step(minutes = 240, goalMinutes = goal))
    }

    @Test
    fun `another goal moves the steps`() {
        assertEquals(3, ReadingGoal.step(minutes = 10, goalMinutes = 10))
        assertEquals(1, ReadingGoal.step(minutes = 40, goalMinutes = 90))
        // Half of 15 is 7.5: 7 is under it, 8 is not.
        assertEquals(1, ReadingGoal.step(minutes = 7, goalMinutes = 15))
        assertEquals(2, ReadingGoal.step(minutes = 8, goalMinutes = 15))
    }

    @Test
    fun `a day read without minutes counted is the first step`() {
        assertEquals(1, ReadingGoal.step(minutes = 0, goalMinutes = 30, read = true))
        assertEquals(0, ReadingGoal.step(minutes = 0, goalMinutes = 30, read = false))
    }

    @Test
    fun `a stored goal out of sense is the default`() {
        assertEquals(30, ReadingGoal.coerce(null))
        assertEquals(30, ReadingGoal.coerce(0))
        assertEquals(30, ReadingGoal.coerce(100_000))
        assertEquals(45, ReadingGoal.coerce(45))
        assertEquals(30, ReadingGoal.DEFAULT_MINUTES)
    }

    @Test
    fun `the steps are named for the goal`() {
        assertEquals("30 min a day", ReadingGoal.label(30))
        assertEquals(
            listOf("No reading", "Under 15 min", "Under 30 min (your goal)", "Goal met", "Twice your goal (60 min or more)"),
            ReadingGoal.stepNames(30),
        )
        assertEquals("Under 7.5 min", ReadingGoal.stepNames(15)[1])
        assertEquals(ReadingGoal.STEPS, ReadingGoal.stepNames(45).size)
    }
}
