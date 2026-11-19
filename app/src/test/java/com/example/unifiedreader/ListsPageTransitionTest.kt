package com.example.unifiedreader

import com.example.unifiedreader.ui.components.AppScreenTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class ListsPageTransitionTest {
    private val lists = ListsPage(listId = null, editing = false)
    private val list = ListsPage(listId = "want-to-read", editing = false)
    private val editor = ListsPage(listId = "want-to-read", editing = true)
    private val newSmartList = ListsPage(listId = null, editing = true)

    @Test
    fun `a list slides in over the lists and back out`() {
        assertEquals(AppScreenTransition.Forward, listsPageTransition(lists, list))
        assertEquals(AppScreenTransition.Backward, listsPageTransition(list, lists))
    }

    @Test
    fun `an editor slides in over its list and back out`() {
        assertEquals(AppScreenTransition.Forward, listsPageTransition(list, editor))
        assertEquals(AppScreenTransition.Backward, listsPageTransition(editor, list))
        assertEquals(AppScreenTransition.Forward, listsPageTransition(lists, editor))
    }

    @Test
    fun `a new smart list's editor is one level over the lists`() {
        assertEquals(AppScreenTransition.Forward, listsPageTransition(lists, newSmartList))
        // Creating it opens the new list in the editor's place.
        assertEquals(AppScreenTransition.Fade, listsPageTransition(newSmartList, ListsPage(listId = "new", editing = false)))
    }

    @Test
    fun `one list to another fades`() {
        assertEquals(AppScreenTransition.Fade, listsPageTransition(list, ListsPage(listId = "stalled", editing = false)))
    }
}
