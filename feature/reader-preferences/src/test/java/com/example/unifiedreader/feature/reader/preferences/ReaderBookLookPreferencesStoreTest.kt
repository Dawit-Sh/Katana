package com.example.unifiedreader.feature.reader.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBookLookPreferencesStoreTest {
    @Test
    fun encodedLookReadsBackTheSameValues() {
        val look = mapOf("epub.fontFamily" to "Literata", "epub.fontSize" to "1.2", "epub.lineHeight" to "1.6")

        assertEquals(look, decodeBookLook(encodeBookLook(look)))
    }

    @Test
    fun nothingSavedReadsAsNoLook() {
        assertEquals(emptyMap<String, String>(), decodeBookLook(null))
        assertEquals(emptyMap<String, String>(), decodeBookLook(""))
    }

    @Test
    fun brokenLinesAreSkipped() {
        assertEquals(mapOf("epub.fontSize" to "1.1"), decodeBookLook("garbage\nepub.fontSize=1.1\n=2\nepub.lineHeight="))
    }

    @Test
    fun inMemoryStoreFollowsTheLastSave() {
        val store = InMemoryReaderBookLookPreferencesStore()

        store.save(mapOf("epub.fontSize" to "1.3"))

        assertEquals(mapOf("epub.fontSize" to "1.3"), store.lookFlow.value.values)
    }
}
