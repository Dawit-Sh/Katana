package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EpubBookLookDescriptionTest {
    private lateinit var savedLocale: Locale

    @Before
    fun useEnglish() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `nothing saved describes as not saved`() {
        assertEquals(ReaderBookLookDescription.NotSaved, describeEpubBookLook(emptyMap()))
        assertFalse(describeEpubBookLook(mapOf("epub.theme" to "Sepia")).isSaved)
    }

    @Test
    fun `a saved look reads as the Aa panel words it`() {
        val look = EpubReaderPreferences(
            fontFamily = EpubReaderFontFamily.Literata,
            fontSize = 1.1,
            lineHeight = 1.6,
            pageMargins = 1.0,
            paragraphSpacing = 0.5,
            paragraphIndent = 2.0,
            textAlign = EpubReaderTextAlign.Justify,
            hyphens = true,
        ).newBookLook()

        val description = describeEpubBookLook(look)

        assertTrue(description.isSaved)
        assertEquals("Literata, 110%, Normal margins", description.summary)
        assertEquals(
            listOf(
                "Font" to "Literata",
                "Text size" to "110%",
                "Line height" to "1.6×",
                "Alignment" to "Justify",
                "Hyphenation" to "On",
                "Margins" to "Normal",
                "Paragraph spacing" to "Small",
                "Paragraph indent" to "Medium",
                "Pages" to "Turn",
                "Page turn" to "Slide",
            ),
            description.lines.map { it.label to it.value },
        )
    }

    @Test
    fun `the book's own typography leaves out the font and spacing`() {
        val look = EpubReaderPreferences(typographyMode = EpubTypographyMode.Original).newBookLook()

        val description = describeEpubBookLook(look)

        assertEquals("Book's own font and spacing", description.summary)
        assertEquals(listOf("Typography", "Pages", "Page turn"), description.lines.map { it.label })
    }

    @Test
    fun `a look saved while scrolling opens new books scrolling, and says so`() {
        val look = EpubReaderPreferences(layoutMode = EpubReaderLayoutMode.Scroll, layoutModeOverride = true).newBookLook()

        val pages = describeEpubBookLook(look).lines.associate { it.label to it.value }
        val opened = restoreEpubPreferences(session = null, newBookLook = look)

        assertEquals("Scroll", pages["Pages"])
        assertFalse(pages.containsKey("Two pages"))
        assertEquals(EpubReaderLayoutMode.Scroll, opened.layoutMode)
        assertTrue(opened.layoutModeOverride)
    }

    @Test
    fun `two pages shows when it is set, and Auto keeps following the orientation`() {
        val always = EpubReaderPreferences(layoutMode = EpubReaderLayoutMode.DoublePage, layoutModeOverride = true).newBookLook()
        val auto = EpubReaderPreferences(layoutMode = EpubReaderLayoutMode.SinglePage, layoutModeOverride = false).newBookLook()

        assertEquals("On", describeEpubBookLook(always).lines.single { it.label == "Two pages" }.value)
        assertFalse(describeEpubBookLook(auto).lines.any { it.label == "Two pages" })
        assertFalse(restoreEpubPreferences(session = null, newBookLook = auto).layoutModeOverride)
    }
}
