package com.example.unifiedreader.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.unifiedreader.core.model.BookMetadata
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.LibraryBrowseChip
import com.example.unifiedreader.core.model.LibraryBrowseRow
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBookPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val book = ReadableItem(
        id = "grimmory:item:1",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:books",
        remoteId = "1",
        title = "The Salt Orchard",
        format = FormatType.EPUB,
        description = "<p>A harvest that never ends.</p>",
        bookMetadata = BookMetadata(author = "Mira Kalden", genres = listOf("Fantasy"), languages = listOf("English")),
    )

    @Test
    fun pageShowsTheBookAndRunsItsActions() {
        var downloaded: ReadableItem? = null
        var markedRead: Boolean? = null
        var picked: ReadStatus? = null
        var filtered: LibraryBrowseChip? = null
        var opened: ReadableItem? = null
        val status = LibraryBookStatus(own = null, choices = ReadStatus.entries, onSelect = { picked = it })

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLibraryBookPageStatus provides status) {
                    LibraryBookPage(
                        item = book,
                        relatedPool = listOf(book),
                        actionContext = LibraryItemActionContext(
                            downloadableItemIds = setOf(book.id),
                            onDownloadItem = { downloaded = it },
                            onSetReadStatus = { _, read -> markedRead = read },
                        ),
                        coverImageUrlProvider = { _, _ -> null },
                        onOpen = { opened = it },
                        onShowBook = {},
                        onDeleteImported = {},
                        onDismiss = {},
                        onFilterByTag = { filtered = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("The Salt Orchard").assertExists()
        composeRule.onNodeWithText("Mira Kalden").assertExists()
        composeRule.onNodeWithText("A harvest that never ends.").assertExists()
        composeRule.onNodeWithText("English").assertExists()

        composeRule.onNodeWithText("Download").performClick()
        // The reading status pill: a book never started is Unread; Read is Mark finished, the others only the status.
        composeRule.onNodeWithText("Unread").performClick()
        composeRule.onNodeWithText("Read").performClick()
        composeRule.onNodeWithText("Unread").performClick()
        composeRule.onNodeWithText("Paused").performClick()
        // A genre filters the library by it and closes the page.
        composeRule.onNodeWithText("Fantasy").performClick()
        composeRule.runOnIdle {
            assertEquals(book, downloaded)
            assertEquals(true, markedRead)
            assertEquals(ReadStatus.PAUSED, picked)
            assertEquals(LibraryBrowseChip(LibraryBrowseRow.GENRE, "Fantasy"), filtered)
            assertEquals(null, opened)
        }
    }

    @Test
    fun startReadingOpensTheBook() {
        var opened: ReadableItem? = null

        composeRule.setContent {
            MaterialTheme {
                LibraryBookPage(
                    item = book,
                    relatedPool = listOf(book),
                    actionContext = LibraryItemActionContext(),
                    coverImageUrlProvider = { _, _ -> null },
                    onOpen = { opened = it },
                    onShowBook = {},
                    onDeleteImported = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Start reading").performClick()
        composeRule.runOnIdle { assertEquals(book, opened) }
    }
}
