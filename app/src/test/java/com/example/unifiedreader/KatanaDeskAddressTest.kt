package com.example.unifiedreader

import org.junit.Assert.assertEquals
import org.junit.Test

class KatanaDeskAddressTest {
    @Test
    fun `an address without a port gets Katana Desk's`() {
        assertEquals("192.168.1.20:47615", "192.168.1.20".normalizedDeskAddress())
        assertEquals("10.0.2.2:47615", " http://10.0.2.2/ ".normalizedDeskAddress())
    }

    @Test
    fun `an address with a port keeps it`() {
        assertEquals("10.0.2.2:5000", "10.0.2.2:5000".normalizedDeskAddress())
        assertEquals("http://desk.local:47615", deskBaseUrl("desk.local"))
    }

    @Test
    fun `the books line counts the books on the desk`() {
        assertEquals("1 book here is on it too.", booksOnDeskText(1))
        assertEquals("3 books here are on it too.", booksOnDeskText(3))
    }
}
