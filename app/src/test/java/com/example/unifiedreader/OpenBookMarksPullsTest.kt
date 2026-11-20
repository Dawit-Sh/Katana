package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceConnectivityState.Checking
import com.example.unifiedreader.core.model.SourceConnectivityState.Offline
import com.example.unifiedreader.core.model.SourceConnectivityState.Online
import com.example.unifiedreader.core.model.SourceConnectivityState.Unknown
import com.example.unifiedreader.OpenBookMarksPulls.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenBookMarksPullsTest {
    @Test
    fun `a book opened while Grimmory answers gets its marks once`() {
        val pulls = OpenBookMarksPulls()

        assertEquals(Step.Pull(setOf("hole")), pulls.next(setOf("hole"), Online))
        assertEquals(Step.Wait, pulls.next(setOf("hole"), Online))
        // Not known yet (just started): try, as before.
        assertEquals(Step.Pull(setOf("alice")), pulls.next(setOf("hole", "alice"), Unknown))
    }

    // The Pixel on 2026-11-20: Grimmory marked offline by a Wi-Fi drop at 1:06 PM, a book opened at 2:02 PM got nothing.
    @Test
    fun `a book opened while Grimmory is marked offline checks it once and gets its marks when it answers`() {
        val pulls = OpenBookMarksPulls()

        assertEquals(Step.CheckServer, pulls.next(setOf("pdf"), Offline))
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Checking))
        // Still offline: no second check for the same opening, so nothing loops while away from home.
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Offline))
        // The retry timer or coming back on screen finds Grimmory again: the book still open gets its marks.
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Checking))
        assertEquals(Step.Pull(setOf("pdf")), pulls.next(setOf("pdf"), Online))
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Online))
    }

    @Test
    fun `a book closed and opened again starts over`() {
        val pulls = OpenBookMarksPulls()

        assertEquals(Step.CheckServer, pulls.next(setOf("pdf"), Offline))
        assertEquals(Step.Wait, pulls.next(emptySet(), Offline))
        assertEquals(Step.CheckServer, pulls.next(setOf("pdf"), Offline))
        assertEquals(Step.Pull(setOf("pdf")), pulls.next(setOf("pdf"), Online))
        assertEquals(Step.Wait, pulls.next(emptySet(), Online))
        assertEquals(Step.Pull(setOf("pdf")), pulls.next(setOf("pdf"), Online))
    }

    @Test
    fun `a second book opened while offline checks again, the first doesn't`() {
        val pulls = OpenBookMarksPulls()

        assertEquals(Step.CheckServer, pulls.next(setOf("a"), Offline))
        assertEquals(Step.Wait, pulls.next(setOf("a"), Offline))
        assertEquals(Step.CheckServer, pulls.next(setOf("a", "b"), Offline))
        assertEquals(Step.Pull(setOf("a", "b")), pulls.next(setOf("a", "b"), Online))
    }

    // The Pixel at 2:17 PM: the Wi-Fi dropped as the PDF opened, so its pull timed out with Grimmory still shown online.
    @Test
    fun `a pull that didn't get through comes down when Grimmory answers again, without reopening`() {
        val pulls = OpenBookMarksPulls()

        assertEquals(Step.Pull(setOf("pdf")), pulls.next(setOf("pdf"), Online))
        pulls.failed(setOf("pdf"))
        // The failure is checked, found offline, and the book asks once for a check of its own.
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Checking))
        assertEquals(Step.CheckServer, pulls.next(setOf("pdf"), Offline))
        assertEquals(Step.Wait, pulls.next(setOf("pdf"), Offline))
        assertEquals(Step.Pull(setOf("pdf")), pulls.next(setOf("pdf"), Online))
    }
}
