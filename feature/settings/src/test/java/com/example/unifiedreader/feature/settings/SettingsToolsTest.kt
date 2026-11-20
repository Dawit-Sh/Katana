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
    fun `books imported from this device count too, with their own part`() {
        val state = StorageSettingsState(importedBookBytes = 14_000_000, isLoaded = true)

        assertEquals("Katana uses 14 MB", storageHeadline(state))
        assertEquals("Imported books" to 14_000_000L, storageParts(state).last().let { it.label to it.bytes })
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
            librarySettingsSummary(listOf(SourceType.KAVITA, SourceType.LOCAL), mapOf(SourceType.KAVITA to one)),
        )
    }

    @Test
    fun `with only books on this device the library row names Local, never a server not added`() {
        val noServers = SourceType.entries.filter { it != SourceType.LOCAL }.map { source -> entry(source, signedIn = false) }
        val order = setUpSourceOrder(SourceType.entries, noServers)
        assertEquals(listOf(SourceType.LOCAL), order)
        val localCount = resolveLibraryFilterPanels(SourceType.LOCAL, preferredPanels = null).size
        assertEquals("$localCount filters in Local", librarySettingsSummary(order, emptyMap()))
        assertEquals("$localCount in Local", filterChipsSummary(order, emptyMap()))
    }

    @Test
    fun `a signed-in server counts as set up, in the sources' order`() {
        val sources = listOf(entry(SourceType.KAVITA, signedIn = false), entry(SourceType.GRIMMORY, signedIn = true))
        assertEquals(
            listOf(SourceType.LOCAL, SourceType.GRIMMORY),
            setUpSourceOrder(listOf(SourceType.KAVITA, SourceType.LOCAL, SourceType.GRIMMORY), sources),
        )
    }

    private fun entry(source: SourceType, signedIn: Boolean) = SourceSettingsEntry(
        sourceType = source,
        connection = RemoteSourceConnectionUiState(source = source, hasStoredSession = signedIn, isSessionKnown = true),
        actions = RemoteSourceSettingsActions(
            onDisplayNameChanged = {},
            onBaseUrlChanged = {},
            onBackupBaseUrlChanged = {},
            onUsernameChanged = {},
            onPasswordChanged = {},
            onConnect = {},
            onSync = {},
            onPreloadCovers = {},
            onRetryFailedCovers = {},
            onCancelCoverPreload = {},
            onClearCoverCache = {},
        ),
    )

    @Test
    fun `at 200 percent text the Theme row drops its preview so the words keep the room`() {
        assertEquals(true, showsThemePreview(fontScale = 1f))
        assertEquals(false, showsThemePreview(fontScale = 2f))
    }

    @Test
    fun `the confirm titles count the books`() {
        assertEquals("Remove 1 finished book?", StorageCopy.removeFinishedTitle(1))
        assertEquals("Remove 4 finished books?", StorageCopy.removeFinishedTitle(4))
        assertEquals("Sign out of Grimmory?", SignOutCopy.dialogTitle("Grimmory"))
    }
}
