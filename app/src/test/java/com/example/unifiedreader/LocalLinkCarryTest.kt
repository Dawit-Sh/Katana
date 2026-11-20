package com.example.unifiedreader

import com.example.unifiedreader.core.database.dao.LocalServerLinkDao
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_CHANGED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_LINKED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_NOT_FOUND
import com.example.unifiedreader.core.database.entity.LocalServerLinkEntity
import com.example.unifiedreader.core.database.entity.RemoteFileCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLinkCarryTest {
    private val linked = LocalServerLinkEntity(
        localItemId = "document-old",
        serverKey = "grimmory@zima",
        state = LOCAL_LINK_LINKED,
        remoteBookId = "2694",
        fileSizeBytes = 968_000,
        partialMd5 = "abc",
        remoteFileSizeKb = 945,
        rejectedBookIds = "",
        checkedAtEpochMillis = 1,
    )

    @Test
    fun `a linked book's link goes to its new id and the old row stays, marked changed`() {
        val dao = FakeLinks(linked)

        dao.carryLink("document-old", "document-new", nowEpochMillis = 99)

        assertEquals(linked.copy(localItemId = "document-new"), dao.getLink("document-new"))
        assertEquals(linked.copy(state = LOCAL_LINK_CHANGED, checkedAtEpochMillis = 99), dao.getLink("document-old"))
    }

    @Test
    fun `the new id keeps a link of its own`() {
        val own = linked.copy(localItemId = "document-new", state = LOCAL_LINK_NOT_FOUND, remoteBookId = null)
        val dao = FakeLinks(linked, own)

        dao.carryLink("document-old", "document-new", nowEpochMillis = 99)

        assertEquals(own, dao.getLink("document-new"))
        assertEquals(LOCAL_LINK_CHANGED, dao.getLink("document-old")?.state)
    }

    @Test
    fun `nothing to carry and the same id change nothing`() {
        val dao = FakeLinks(linked)

        dao.carryLink("document-none", "document-new", nowEpochMillis = 99)
        dao.carryLink("document-old", "document-old", nowEpochMillis = 99)

        assertNull(dao.getLink("document-new"))
        assertEquals(linked, dao.getLink("document-old"))
    }

    private class FakeLinks(vararg rows: LocalServerLinkEntity) : LocalServerLinkDao {
        private val rows = rows.associateBy(LocalServerLinkEntity::localItemId).toMutableMap()

        override fun observeLinks(): Flow<List<LocalServerLinkEntity>> = flowOf(rows.values.toList())

        override fun getLink(localItemId: String): LocalServerLinkEntity? = rows[localItemId]

        override fun upsertLink(link: LocalServerLinkEntity) {
            rows[link.localItemId] = link
        }

        override fun getCandidates(sourceType: String, format: String, sizeKb: Long): List<RemoteFileCandidate> = emptyList()

        override fun getCatalogBook(sourceType: String, remoteId: String): RemoteFileCandidate? = null
    }
}
