package com.example.unifiedreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KatanaDeskPairFormTest {
    private val pc = KatanaDeskFound(name = "DADDYMARX", address = "192.168.100.199:47615", deskId = "d-1")
    private val laptop = KatanaDeskFound(name = "LAPTOP", address = "192.168.100.30:47615", deskId = "d-2")

    @Test
    fun `the desk found fills in the address, so only the code is typed`() {
        val form = KatanaDeskPairForm().withFound(listOf(pc), stillLooking = true)
        assertEquals(pc.address, form.address)
        assertFalse(form.showsAddressField)
        assertEquals(pc, form.desk)
        assertFalse(form.canPair)
        assertTrue(form.codeTyped("123456").canPair)
    }

    @Test
    fun `the code takes digits only, six at most`() {
        assertEquals("123456", KatanaDeskPairForm().codeTyped("12 34-5678").code)
        assertFalse(KatanaDeskPairForm(address = "a").codeTyped("12345").canPair)
    }

    @Test
    fun `a picked desk stays picked as more are found`() {
        val form = KatanaDeskPairForm().withFound(listOf(pc, laptop), stillLooking = true).picked(laptop)
        assertEquals(laptop.address, form.withFound(listOf(pc, laptop), stillLooking = false).address)
    }

    @Test
    fun `nothing found means the address is typed instead`() {
        val looking = KatanaDeskPairForm()
        assertFalse(looking.showsAddressField)
        val nothing = looking.withFound(emptyList(), stillLooking = false)
        assertTrue(nothing.nothingFound)
        assertTrue(nothing.showsAddressField)
        val typed = nothing.addressTyped("10.0.2.2")
        // a desk found later doesn't take a typed address over
        assertEquals("10.0.2.2", typed.withFound(listOf(pc), stillLooking = false).address)
    }

    @Test
    fun `a code that ran out stops pairing, empties the code and says why`() {
        val pairing = KatanaDeskPairForm().withFound(listOf(pc), stillLooking = false).codeTyped("123456").started()
        assertTrue(pairing.pairing)
        assertFalse(pairing.canPair)
        val refused = pairing.refused(ranOut())
        assertFalse(refused.pairing)
        assertEquals("", refused.code)
        assertEquals(KatanaDeskPairRefusal.CodeRanOut, refused.problem?.refusal)
        // typing a new code clears the message, and pairs again
        val again = refused.codeTyped("654321")
        assertNull(again.problem)
        assertTrue(again.canPair)
    }

    @Test
    fun `too many tries keeps the code, a desk gone opens the address`() {
        val form = KatanaDeskPairForm().withFound(listOf(pc), stillLooking = false).codeTyped("123456").started()
        val locked = form.refused(KatanaDeskRefused("Too many tries.", KatanaDeskPairRefusal.TooManyTries))
        assertEquals("123456", locked.code)
        val gone = form.refused(noAnswerRefusal(pc.address))
        assertEquals(KatanaDeskPairRefusal.NoAnswer, gone.problem?.refusal)
        assertTrue(gone.showsAddressField)
        assertFalse(gone.pairing)
    }

    @Test
    fun `the desks found are one row each, on this device's own network`() {
        val own = intArrayOf(192, 168, 100, 23)
        val finds = KatanaDeskFinds(own)
        // DNS-SD gave the PC's VirtualBox address first; the network look finds its Wi-Fi one
        assertTrue(finds.add(pc.copy(address = "192.168.56.1:47615")))
        assertTrue(finds.add(pc))
        assertFalse(finds.add(pc.copy(address = "172.23.80.1:47615")))
        assertTrue(finds.add(laptop))
        assertEquals(listOf(pc, laptop), finds.list)
    }

    @Test
    fun `addresses and networks`() {
        assertTrue(onOwnNetwork("192.168.100.199:47615", intArrayOf(192, 168, 100, 23)))
        assertFalse(onOwnNetwork("192.168.56.1:47615", intArrayOf(192, 168, 100, 23)))
        assertTrue(onOwnNetwork("10.0.2.2:47615", intArrayOf(10, 0, 2, 16)))
        assertFalse(onOwnNetwork("desk.local:47615", intArrayOf(10, 0, 2, 16)))
        assertFalse(onOwnNetwork("10.0.2.2", null))
        assertNull(ipv4Of("300.1.1.1"))
        assertEquals(listOf(10, 0, 2, 2), ipv4Of("http://10.0.2.2:47615/")?.toList())
    }

    @Test
    fun `a place said again with a new time is not a move`() {
        val here = com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot(
            itemId = "b",
            locatorType = com.example.unifiedreader.core.model.LocatorType.CFI,
            cfi = "epubcfi(/6/16!/4/2)",
            percent = 0.58,
            updatedAtEpochMillis = 1L,
        )
        assertFalse(here.copy(updatedAtEpochMillis = 2L, syncState = com.example.unifiedreader.core.model.SyncState.DIRTY).movedFrom(here))
        assertFalse(here.copy(percent = 0.580001).movedFrom(here))
        assertTrue(here.copy(percent = 0.62).movedFrom(here))
        assertTrue(here.copy(cfi = "epubcfi(/6/18!/4/2)").movedFrom(here))
        assertTrue(here.movedFrom(null))
        assertFalse(null.movedFrom(null))
    }

    private fun ranOut() = KatanaDeskRefused("That code ran out.", KatanaDeskPairRefusal.CodeRanOut)
}
