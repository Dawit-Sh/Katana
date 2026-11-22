package com.example.unifiedreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBookPlacesTest {
    @Test
    fun `a picked folder reads as its path on the phone, or on the SD card`() {
        assertEquals("Download/Katana-formats-test", folderLabelOf("primary:Download/Katana-formats-test"))
        assertEquals("Books", folderLabelOf("primary:Books"))
        assertEquals("This device's storage", folderLabelOf("primary:"))
        assertEquals("SD card · Books/Comics", folderLabelOf("1A2B-3C4D:Books/Comics"))
        assertEquals("SD card", folderLabelOf("1A2B-3C4D:"))
    }

    // The Fire (Android 9), 2026-11-20: a folder picked under Downloads read "SD card · storage/emulated/0/Download/…".
    @Test
    fun `a folder picked under Downloads on older Android reads as its path`() {
        assertEquals("Download/Katana-formats-test", folderLabelOf("raw:/storage/emulated/0/Download/Katana-formats-test"))
        assertEquals("SD card · Books", folderLabelOf("raw:/storage/1A2B-3C4D/Books"))
        assertEquals("This device's storage", folderLabelOf("raw:/storage/emulated/0"))
        assertEquals("Download", folderLabelOf("downloads"))
        assertEquals("1234", folderLabelOf("msd:1234"))
    }

    @Test
    fun `only book files are looked at`() {
        listOf("Frankenstein.mobi", "a.EPUB", "scan.djvu", "x.djv", "c.cbz", "c.CBR", "p.pdf", "k.azw3", "k.azw", "w.fb2")
            .forEach { name -> assertTrue(name, name.isBookFileName()) }
        listOf("notes.txt", "cover.jpg", "book.epub.part", "archive.zip", "noextension")
            .forEach { name -> assertFalse(name, name.isBookFileName()) }
    }

    @Test
    fun `a places list with nothing in it says so`() {
        assertFalse(LocalBookPlacesShown(folders = emptyList(), wholePhone = false).any)
        assertTrue(LocalBookPlacesShown(folders = emptyList(), wholePhone = true).any)
    }

    @Test
    fun `the toast after removing says the files stay`() {
        assertEquals("Removed, with its 1 book. The file stays on this device", removedToastText(1))
        assertEquals("Removed, with its 3 books. The files stay on this device", removedToastText(3))
    }
}
