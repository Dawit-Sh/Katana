package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationJumpsTest {
    @Test
    fun `a place is taken once, and only by its own book`() {
        val jumps = AnnotationJumps()
        jumps.request("grimmory:item:2694", "{\"href\":\"c1.xhtml\"}")

        assertNull(jumps.take("grimmory:item:7"))
        assertEquals("{\"href\":\"c1.xhtml\"}", jumps.take("grimmory:item:2694"))
        assertNull(jumps.take("grimmory:item:2694"))
    }

    @Test
    fun `a newer place replaces the waiting one, and clear drops it`() {
        val jumps = AnnotationJumps()
        jumps.request("a", "1")
        jumps.request("b", "2")
        assertNull(jumps.take("a"))

        jumps.clear()
        assertNull(jumps.take("b"))
    }
}
