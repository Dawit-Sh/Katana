package com.example.unifiedreader.feature.settings

import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.resolveLibraryFilterPanels
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsToolsTest {
    @Test
    fun `the look row says the look, or what new books open with without one`() {
        val saved = ReaderBookLookDescription(
            summary = "Literata, 110%, Normal margins",
            lines = listOf(ReaderBookLookLine("Font", "Literata")),
        )
        assertEquals("Literata, 110%, Normal margins", newBookLookSummary(saved))
        assertEquals(
            "Not set: books open with the reader's own look",
            newBookLookSummary(ReaderBookLookDescription.NotSaved),
        )
    }

    @Test
    fun `storage adds up what Katana keeps and says what is free`() {
        val state = StorageSettingsState(
            downloadedBookBytes = 1_000_000_000,
            keptBookBytes = 200_000_000,
            coverBytes = 310_000_000,
            freeBytes = 9_800_000_000,
            isLoaded = true,
        )

        assertEquals("Katana uses 1.5 GB", storageHeadline(state))
        assertEquals("9.8 GB free", storageFreeLabel(state))
        assertEquals(
            listOf("Downloads" to 1_000_000_000L, "Kept from opening" to 200_000_000L, "Covers" to 310_000_000L),
            storageParts(state).map { it.label to it.bytes },
        )
    }

    @Test
    fun `storage waits for its measurement before it says anything`() {
        val state = StorageSettingsState(freeBytes = 5_000)
        assertEquals("Measuring", storageHeadline(state))
        assertNull(storageFreeLabel(state))
    }

    @Test
    fun `the library row names the first source's filters and that it comes first`() {
        val grimmoryCount = resolveLibraryFilterPanels(SourceType.GRIMMORY, preferredPanels = null).size
        assertEquals(
            "$grimmoryCount filters in Grimmory · Grimmory first",
            librarySettingsSummary(listOf(SourceType.GRIMMORY, SourceType.LOCAL), emptyMap()),
        )
        val one = listOf(resolveLibraryFilterPanels(SourceType.KAVITA, preferredPanels = null).first())
        assertEquals(
            "1 filter in Kavita · Kavita first",
            librarySettingsSummary(listOf(SourceType.KAVITA), mapOf(SourceType.KAVITA to one)),
        )
    }

    @Test
    fun `the confirm titles count the books`() {
        assertEquals("Remove 1 finished book?", StorageCopy.removeFinishedTitle(1))
        assertEquals("Remove 4 finished books?", StorageCopy.removeFinishedTitle(4))
        assertEquals("Sign out of Grimmory?", SignOutCopy.dialogTitle("Grimmory"))
    }
}
