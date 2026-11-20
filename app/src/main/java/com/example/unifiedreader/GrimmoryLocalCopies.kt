package com.example.unifiedreader

import android.content.Context
import android.util.Log
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.database.dao.LocalServerLinkDao
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_LINKED
import com.example.unifiedreader.core.database.entity.LocalServerLinkEntity
import com.example.unifiedreader.core.database.entity.RemoteFileCandidate
import com.example.unifiedreader.core.model.PartialMd5
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.runSuspendCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the book page says about a local book's marks. */
enum class LocalMarksHome {
    /** A copy of a Grimmory book: its marks sync with it. */
    SyncsWithGrimmory,

    /** Checked: no Grimmory book is this file (or it was, and the file changed). */
    NotOnGrimmory,

    /** Its marks stay here and there's nothing to check (no Grimmory, or a format whose marks don't sync). */
    OnThisDevice,
}

/**
 * Local books that are copies of the active Grimmory server's books ([LocalCopyMatcher] says how, and
 * why it never links two different books), checked when a book opens, when its page shows and before
 * every sync of its marks. [isLinked] answers at once, from the last check.
 */
@Singleton
class GrimmoryLocalCopies @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val dao: LocalServerLinkDao,
    private val localDocuments: LocalDocumentDataSource,
    private val grimmoryApiFactory: GrimmoryApiFactory,
    private val credentialStore: GrimmoryCredentialStore,
    private val connectivity: RemoteSourceConnectivityPolicy,
) {
    private val links = MutableStateFlow<Map<String, LocalServerLinkEntity>>(emptyMap())
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val sessionRequestRunner = GrimmoryStoredSessionRequestRunner(credentialStore)
    private val matcher = LocalCopyMatcher(
        store = RoomLocalCopyLinkStore(dao),
        fingerprintOf = ::fingerprintOf,
        serverFingerprintOf = ::serverFingerprintOf,
    )

    /** The links as last checked, by local book. */
    val linkStates: StateFlow<Map<String, LocalServerLinkEntity>> = links.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { dao.observeLinks().collect { rows -> links.value = rows.associateBy { it.localItemId } } }
    }

    /** Whether [item] was a copy of a Grimmory book at its last check (an EPUB's reader then places its marks the server's way). */
    fun isLinked(item: ReadableItem): Boolean = item.sourceType == SourceType.LOCAL && links.value[item.id]?.state == LOCAL_LINK_LINKED

    /** [item] as a copy of a Grimmory book whose marks sync with it, checked now; null when it isn't one, or can't be told now. */
    suspend fun copyOf(item: ReadableItem): GrimmoryMarksBook? {
        val canSync = item.sourceType == SourceType.LOCAL && item.format.sharesBookmarksWithGrimmory()
        val session = credentialStore.sessionFlow.value?.takeIf { canSync } ?: return null
        val canAskServer = !connectivity.isUnavailable(SourceType.GRIMMORY)
        val checked = mutex.withLock {
            runSuspendCatching { matcher.check(item, session.serverKey(), canAskServer) }
                .onFailure { failure -> Log.w(LOG_TAG, "Couldn't check ${item.id} against Grimmory.", failure) }
                .getOrNull()
        }
        return (checked as? LocalCopyCheck.Linked)?.let { linked -> GrimmoryMarksBook(item, linked.bookId) }
    }

    /** The local books among [items] that are copies of Grimmory books, each checked now. */
    suspend fun copiesAmong(items: List<ReadableItem>): List<GrimmoryMarksBook> = items.mapNotNull { item -> copyOf(item) }

    /** The local books linked at their last check, as candidates for [copiesAmong]. */
    fun linkedItemIds(): Set<String> = links.value.filterValues { it.state == LOCAL_LINK_LINKED }.keys

    /** The books on this device that are files here (not a server's). */
    fun localItems(): List<ReadableItem> = localDocuments.getImportedItems()

    /** What [item]'s page says about its marks. */
    fun marksHome(item: ReadableItem, link: LocalServerLinkEntity?): LocalMarksHome = when {
        !item.format.sharesBookmarksWithGrimmory() || credentialStore.sessionFlow.value == null -> LocalMarksHome.OnThisDevice
        link?.state == LOCAL_LINK_LINKED -> LocalMarksHome.SyncsWithGrimmory
        link != null -> LocalMarksHome.NotOnGrimmory
        else -> LocalMarksHome.OnThisDevice
    }

    // The file's size and fingerprint, read through its content URI: twelve reads of 1 KB.
    private suspend fun fingerprintOf(item: ReadableItem): LocalFileFingerprint? = withContext(Dispatchers.IO) {
        val uri = localDocuments.getDocumentUri(item) ?: return@withContext null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val size = descriptor.statSize.takeIf { it > 0 } ?: return@use null
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    val md5 = PartialMd5.of(size) { start, length ->
                        val buffer = ByteBuffer.allocate(length)
                        while (buffer.hasRemaining() && channel.read(buffer, start + buffer.position()) > 0) Unit
                        buffer.array().copyOf(buffer.position())
                    }
                    LocalFileFingerprint(size, md5)
                }
            }
        } catch (unreadable: IOException) {
            Log.w(LOG_TAG, "Couldn't read ${item.id} to fingerprint it.", unreadable)
            null
        } catch (denied: SecurityException) {
            Log.w(LOG_TAG, "No access to ${item.id} to fingerprint it.", denied)
            null
        }
    }

    // The server file's twelve samples, by Range requests through the saved sign-in.
    private suspend fun serverFingerprintOf(bookId: String, sizeBytes: Long): RemoteFileFingerprint? {
        val session = credentialStore.sessionFlow.value ?: return null
        val samples = runWithStoredSessionAddressFailover(
            sessionStore = credentialStore,
            session = session,
            createApi = grimmoryApiFactory::create,
        ) { api, activeSession ->
            sessionRequestRunner.run(api = api, session = activeSession) { token ->
                api.files.readSamples(token, bookId, PartialMd5.ranges(sizeBytes))
            }
        }
        return samples?.let { RemoteFileFingerprint(it.fileSize, PartialMd5.of(it.samples)) }
    }

    private companion object {
        const val LOG_TAG = "LocalCopies"
    }
}

/** Which server and account a link was made against: the same book number on another server is another book. */
internal fun GrimmoryStoredSession.serverKey(): String = "${username.trim()}@${baseUrl.trim().trimEnd('/')}"

private class RoomLocalCopyLinkStore(private val dao: LocalServerLinkDao) : LocalCopyLinkStore {
    override suspend fun link(localItemId: String): LocalServerLinkEntity? = io { dao.getLink(localItemId) }

    override suspend fun save(link: LocalServerLinkEntity) = io { dao.upsertLink(link) }

    override suspend fun candidates(item: ReadableItem, sizeKb: Long): List<RemoteFileCandidate> =
        io { dao.getCandidates(SourceType.GRIMMORY.name, item.format.name, sizeKb) }

    override suspend fun catalogBook(remoteBookId: String): RemoteFileCandidate? =
        io { dao.getCatalogBook(SourceType.GRIMMORY.name, remoteBookId) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
