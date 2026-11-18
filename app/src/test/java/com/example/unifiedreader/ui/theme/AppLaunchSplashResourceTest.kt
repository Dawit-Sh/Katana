package com.example.unifiedreader.ui.theme

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AppLaunchSplashResourceTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val res = File("src/main/res")

    @Test
    fun `older Android has a static icon and Android 12 has a finite native animation`() {
        assertEquals("vector", read("drawable/launch_mark.xml").tagName)
        val animated = read("drawable-v31/launch_mark.xml")
        assertEquals("animated-vector", animated.tagName)
        val targets = elements(animated, "target")
        val names = (elements(animated, "group") + elements(animated, "path"))
            .map { it.getAttributeNS(android, "name") }.filter { it.isNotBlank() }
        assertEquals("Every animation target must resolve unambiguously", names.size, names.toSet().size)
        targets.forEach { assertTrue(it.getAttributeNS(android, "name") in names) }
        elements(animated, "objectAnimator").forEach {
            assertTrue(it.getAttributeNS(android, "duration").toInt() in 1..1000)
            assertTrue(it.getAttributeNS(android, "repeatCount") in listOf("", "0"))
        }
        assertEquals(listOf("glint"), targets.map { it.getAttributeNS(android, "name") })
    }

    @Test
    fun `the katana is one theme-coloured ink shape and the glint rests hidden over it`() {
        val still = elements(read("drawable/launch_mark.xml"), "path")
        assertEquals(listOf("katana_mark"), still.map { it.getAttributeNS(android, "name") })
        assertEquals("?attr/launchMarkColor", still.single().getAttributeNS(android, "fillColor"))
        assertFalse(still.single().getAttributeNS(android, "fillAlpha") == "0")

        val animated = read("drawable-v31/launch_mark.xml")
        val paths = elements(animated, "path").associateBy { it.getAttributeNS(android, "name") }
        // Drawn in this order: the mark, then the glint's background-coloured gap, then the glint.
        assertEquals(listOf("katana_mark", "glint_halo", "glint_star"), elements(animated, "path").map {
            it.getAttributeNS(android, "name")
        })
        assertEquals("?attr/launchMarkColor", paths.getValue("katana_mark").getAttributeNS(android, "fillColor"))
        assertEquals("?attr/launchPaperColor", paths.getValue("glint_halo").getAttributeNS(android, "fillColor"))
        assertEquals("?attr/launchMarkColor", paths.getValue("glint_star").getAttributeNS(android, "fillColor"))
        val glint = elements(animated, "group").single { it.getAttributeNS(android, "name") == "glint" }
        assertEquals("0", glint.getAttributeNS(android, "scaleX"))
        assertEquals("0", glint.getAttributeNS(android, "scaleY"))
    }

    @Test
    fun `all animation channels return to the starting pose`() {
        elements(read("drawable-v31/launch_mark.xml"), "propertyValuesHolder").forEach { holder ->
            val frames = elements(holder, "keyframe")
            assertEquals("0", frames.first().getAttributeNS(android, "fraction"))
            assertEquals("1", frames.last().getAttributeNS(android, "fraction"))
            assertEquals(holder.getAttributeNS(android, "propertyName"),
                frames.first().getAttributeNS(android, "value").toFloat(),
                frames.last().getAttributeNS(android, "value").toFloat(), .00001f)
        }
    }

    private fun read(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(res, path)).documentElement

    private fun elements(root: Element, tag: String): List<Element> = root.getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
