package com.example.unifiedreader.feature.reader.pdf

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.feature.reader.core.ReaderLocator
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfProgressSaveTest {
    private val pdf = ReadableItem(
        id = "grimmory:3426",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:1",
        title = "A PDF",
        format = FormatType.PDF,
    )

    // The Fire, 2026-11-20: page 6 was sent, closing the book saved page 6 again as a new read, and that
    // put page 6 back over a page 2 read on another device eight seconds after it.
    @Test
    fun `closing on the page already saved doesn't make it a new read`() {
        val store = PdfTestReaderSessionStore()
        store.savePdfProgress(item = pdf, pageIndex = 5, pageCount = 589)
        val saved = requireNotNull(store.getProgress(pdf))
        store.replaceProgressNow(saved.copy(syncState = SyncState.CLEAN))

        store.savePdfProgress(item = pdf, pageIndex = 5, pageCount = 589)

        val now = requireNotNull(store.getProgress(pdf))
        assertEquals(SyncState.CLEAN, now.syncState)
        assertEquals(saved.updatedAtEpochMillis, now.updatedAtEpochMillis)
    }

    @Test
    fun `a new page or finishing is a new read`() {
        val store = PdfTestReaderSessionStore()
        store.savePdfProgress(item = pdf, pageIndex = 5, pageCount = 589)
        store.replaceProgressNow(requireNotNull(store.getProgress(pdf)).copy(syncState = SyncState.CLEAN))

        store.savePdfProgress(item = pdf, pageIndex = 6, pageCount = 589)
        assertEquals(SyncState.DIRTY, store.getProgress(pdf)?.syncState)
        assertEquals(6, store.getProgress(pdf)?.page)

        store.replaceProgressNow(requireNotNull(store.getProgress(pdf)).copy(syncState = SyncState.CLEAN))
        store.savePdfProgress(item = pdf, pageIndex = 6, pageCount = 589, isFinished = true)
        assertEquals(SyncState.DIRTY, store.getProgress(pdf)?.syncState)
        assertEquals(true, store.getProgress(pdf)?.isFinished)
        assertEquals(LocatorType.PAGE, store.getProgress(pdf)?.locatorType)
    }
}

private fun PdfTestReaderSessionStore.replaceProgressNow(snapshot: ReaderProgressSnapshot) =
    kotlinx.coroutines.runBlocking { replaceProgressIfCurrent(requireNotNull(getProgress(snapshot.itemId)), snapshot) }

private fun PdfTestReaderSessionStore.getProgress(itemId: String): ReaderProgressSnapshot? = progressByItemIdFlow.value[itemId]

private class PdfTestReaderSessionStore : ReaderSessionStore {
    private val progressState = MutableStateFlow<Map<String, ReaderProgressSnapshot>>(emptyMap())
    private val sessionState = MutableStateFlow<Map<String, ReaderSessionSnapshot>>(emptyMap())

    override val isHydratedFlow: StateFlow<Boolean> = MutableStateFlow(true)
    override val progressByItemIdFlow: StateFlow<Map<String, ReaderProgressSnapshot>> = progressState
    override val sessionByItemIdFlow: StateFlow<Map<String, ReaderSessionSnapshot>> = sessionState

    override fun getProgress(item: ReadableItem): ReaderProgressSnapshot? = progressState.value[item.id]

    override fun saveProgress(
        item: ReadableItem,
        locator: ReaderLocator,
        isFinished: Boolean,
        isExplicitUnreadReset: Boolean,
        syncState: SyncState,
        pageCount: Int?,
        updatedAtEpochMillis: Long,
    ) {
        progressState.value = progressState.value + (
            item.id to ReaderProgressSnapshot(
                itemId = item.id,
                locatorType = locator.locatorType,
                page = locator.page,
                cfi = locator.cfi,
                percent = locator.percent,
                isFinished = isFinished,
                isExplicitUnreadReset = isExplicitUnreadReset,
                syncState = syncState,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        )
        if (pageCount != null) {
            updateSession(item = item, pageCount = pageCount)
        }
    }

    override fun getSession(item: ReadableItem): ReaderSessionSnapshot? = sessionState.value[item.id]

    override suspend fun replaceProgressIfCurrent(
        expected: ReaderProgressSnapshot,
        replacement: ReaderProgressSnapshot?,
    ): Boolean {
        val matches = progressState.value[expected.itemId] == expected
        if (matches) {
            progressState.value = if (replacement == null) {
                progressState.value - expected.itemId
            } else {
                progressState.value + (expected.itemId to replacement)
            }
        }
        return matches
    }

    override fun updateSession(
        itemId: String,
        pageCount: Int?,
        preferences: Map<String, String>,
    ) {
        val existing = sessionState.value[itemId] ?: ReaderSessionSnapshot(itemId = itemId)
        sessionState.value = sessionState.value + (
            itemId to existing.copy(
                pageCount = pageCount ?: existing.pageCount,
                preferences = existing.preferences + preferences,
            )
        )
    }

    override fun clearProgress(itemId: String) {
        progressState.value = progressState.value - itemId
    }

    override fun clearItem(itemId: String) {
        clearProgress(itemId)
        sessionState.value = sessionState.value - itemId
    }

    override fun restoreItem(itemId: String) = Unit
}
