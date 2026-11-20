package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.LocalServerLinkEntity
import com.example.unifiedreader.core.database.entity.RemoteFileCandidate
import kotlinx.coroutines.flow.Flow

/** Which local books are copies of which server books, and the server books a local file could be. Plain functions off the main thread. */
@Dao
interface LocalServerLinkDao {
    @Query("SELECT * FROM local_server_links")
    fun observeLinks(): Flow<List<LocalServerLinkEntity>>

    @Query("SELECT * FROM local_server_links WHERE localItemId = :localItemId")
    fun getLink(localItemId: String): LocalServerLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLink(link: LocalServerLinkEntity)

    /** A [sourceType] server's books of [format] whose file is [sizeKb] KB (rounded down, as servers give it). */
    @Query(
        "SELECT remoteId, remoteFileSizeKb FROM readable_items WHERE sourceType = :sourceType AND format = :format " +
            "AND remoteFileSizeKb = :sizeKb AND remoteId IS NOT NULL ORDER BY remoteId",
    )
    fun getCandidates(sourceType: String, format: String, sizeKb: Long): List<RemoteFileCandidate>

    /** A [sourceType] server's book [remoteId] as the catalog has it now, or null when it's gone. */
    @Query("SELECT remoteId, remoteFileSizeKb FROM readable_items WHERE sourceType = :sourceType AND remoteId = :remoteId LIMIT 1")
    fun getCatalogBook(sourceType: String, remoteId: String): RemoteFileCandidate?
}
