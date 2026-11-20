package com.example.unifiedreader

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.annotations.AnnotationBackupStore
import com.example.unifiedreader.feature.annotations.AnnotationMarkdown
import com.example.unifiedreader.feature.annotations.LocalBookMarksMover
import com.example.unifiedreader.feature.annotations.LocalBooksInBackup
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.downloads.DownloadEntryStatus
import com.example.unifiedreader.feature.downloads.KeptBookActions
import com.example.unifiedreader.feature.downloads.KeptBookCandidate
import com.example.unifiedreader.feature.downloads.KeptBookFiles
import com.example.unifiedreader.feature.downloads.KeptBooksSnapshot
import com.example.unifiedreader.feature.downloads.RemoteDownloadQueueController
import com.example.unifiedreader.feature.downloads.RemoteItemDownloadDataSource
import com.example.unifiedreader.feature.downloads.downloadDataSourceFor
import com.example.unifiedreader.feature.library.covers.GRIMMORY_COVER_DIRECTORY_NAME
import com.example.unifiedreader.feature.library.covers.KAVITA_COVER_DIRECTORY_NAME
import com.example.unifiedreader.feature.library.covers.STUMP_COVER_DIRECTORY_NAME
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.epub.describeEpubBookLook
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription
import com.example.unifiedreader.feature.reader.preferences.ReaderDisplayPreferences
import com.example.unifiedreader.feature.settings.SettingsFileExport
import com.example.unifiedreader.feature.settings.StorageSettingsState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The backup of highlights, words and bookmarks; kept out of [AppModule], which should not grow. */
@Module
@InstallIn(SingletonComponent::class)
object AppSettingsToolsModule {
    @Provides
    @Singleton
    fun provideAnnotationBackupStore(
        @ApplicationContext context: Context,
        database: UnifiedReaderDatabase,
        localBooks: LocalBookImports,
        sessions: RoomReaderSessionStore,
    ): AnnotationBackupStore = AnnotationBackupStore(
        dao = database.readerAnnotationDao(),
        bookmarkDao = database.readerBookmarkDao(),
        local = LocalBooksInBackup(
            directory = localBooks,
            pendingMarks = PendingLocalMarksStore(context),
            readingPlaces = LocalReadingPlaceBackup(
                sessions = sessions,
                hydrate = sessions::hydrate,
                localItems = { localBooks.getImportedItems() },
            ),
            marksMover = localMarksMover(database),
        ),
    )

    /** Imports of books from files that know a file already here, and give a new book its waiting marks. */
    @Provides
    @Singleton
    fun provideLocalBookImports(
        @ApplicationContext context: Context,
        database: UnifiedReaderDatabase,
        repository: LocalDocumentRepository,
        sessions: RoomReaderSessionStore,
    ): LocalBookImports = LocalBookImports(
        documents = repository,
        hydrator = repository,
        fingerprints = LocalFileFingerprints(context),
        marksHoming = LocalMarksHoming(
            pending = PendingLocalMarksStore(context),
            mover = localMarksMover(database),
            places = LocalReadingPlaceBackup(
                sessions = sessions,
                hydrate = sessions::hydrate,
                localItems = { repository.getImportedItems() },
            ),
        ),
        releaseRead = { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        },
    )

    /** Moves a book from a file's marks to its new id, its Grimmory link along with them, in one transaction. */
    private fun localMarksMover(database: UnifiedReaderDatabase) = LocalBookMarksMover { fromItemId, toItemId ->
        withContext(Dispatchers.IO) {
            database.runInTransaction<Int> {
                database.readerAnnotationDao().moveMarks(fromItemId, toItemId).also {
                    database.localServerLinkDao().carryLink(fromItemId, toItemId, System.currentTimeMillis())
                }
            }
        }
    }
}

/** The Downloads sheet's "On this device": the books and what Remove does; no actions without the tools. */
internal class KeptBooksForSheet(
    val snapshot: KeptBooksSnapshot,
    val actions: KeptBookActions?,
)

/** The Katana parts of Settings and the Downloads sheet; null (in tests) leaves them out. */
internal val LocalAppSettingsTools = staticCompositionLocalOf<AppSettingsToolsViewModel?> { null }

/**
 * What the Katana parts of Settings and the Downloads sheet read and do: the look for new books,
 * signing out of a server, the books on the tablet and the space they take, and the highlights'
 * export, backup and restore.
 */
@HiltViewModel
class AppSettingsToolsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val displayPreferences: ReaderDisplayPreferences,
    private val grimmoryCredentialStore: GrimmoryCredentialStore,
    private val kavitaCredentialStore: KavitaCredentialStore,
    private val stumpCredentialStore: StumpCredentialStore,
    private val annotationStore: ReaderAnnotationStore,
    private val annotationBackupStore: AnnotationBackupStore,
    private val readerSessionStore: ReaderSessionStore,
    private val readableItemLookup: ReadableItemLookup,
    private val downloadQueue: RemoteDownloadQueueController,
    private val downloadDataSources: List<@JvmSuppressWildcards RemoteItemDownloadDataSource>,
    private val remoteDocumentReadCache: RemoteDocumentReadCache,
) : ViewModel() {
    private val keptBookFiles = KeptBookFiles(
        dataSources = downloadDataSources,
        extraFiles = { item -> listOfNotNull(remoteDocumentReadCache.cachedDocumentFile(item)) },
    )
    private val scanMutex = Mutex()
    private val keptState = MutableStateFlow(KeptBooksSnapshot.Empty)
    private val coverBytes = MutableStateFlow<Long?>(null)

    val newBookLook: StateFlow<ReaderBookLookDescription> = displayPreferences.bookLook.lookFlow
        .map { look -> describeEpubBookLook(look.values) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderBookLookDescription.NotSaved)

    /** The books on the tablet; [refresh] measures them again. */
    val keptBooks: StateFlow<KeptBooksSnapshot> = keptState.asStateFlow()

    private val storageState = MutableStateFlow(StorageSettingsState())
    val storage: StateFlow<StorageSettingsState> = storageState.asStateFlow()

    fun resetNewBookLook() = displayPreferences.bookLook.save(emptyMap())

    /** Forgets [source]'s saved sign-in; the connection follows the store, so the server leaves the side menu. */
    fun signOut(source: SourceType) {
        val store: SourceSessionStore<*> = when (source) {
            SourceType.GRIMMORY -> grimmoryCredentialStore
            SourceType.KAVITA -> kavitaCredentialStore
            SourceType.STUMP -> stumpCredentialStore
            SourceType.LOCAL -> return
        }
        viewModelScope.launch { runSuspendCatching { store.clear() } }
    }

    /** Measures the books on the tablet again, and with [withCovers] the covers too (Settings › Storage). */
    fun refresh(withCovers: Boolean) {
        viewModelScope.launch { scan(measureCovers = withCovers) }
    }

    /**
     * Deletes the tablet's copies of [itemIds], then tells the library through [onRemoved] (it drops
     * their "on this device" marks) and measures again.
     */
    fun removeKeptBooks(itemIds: List<String>, onRemoved: (Set<String>) -> Unit) {
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            val books = keptState.value.books.filter { book -> book.item.id in itemIds }
            val removed = books
                .filter { book -> runSuspendCatching { keptBookFiles.delete(book.item) }.getOrDefault(false) }
                .mapTo(mutableSetOf()) { book -> book.item.id }
            keptState.value = keptState.value.copy(books = keptState.value.books.filterNot { book -> book.item.id in removed })
            onRemoved(removed)
            scan(measureCovers = coverBytes.value != null)
        }
    }

    fun removeFinishedBooks(onRemoved: (Set<String>) -> Unit) {
        viewModelScope.launch {
            if (!keptState.value.isLoaded) scan(measureCovers = true)
            removeKeptBooks(keptState.value.finishedBooks.map { book -> book.item.id }, onRemoved)
        }
    }

    suspend fun exportMarkdown(): SettingsFileExport {
        val day = LocalDate.now()
        val text = AnnotationMarkdown.library(
            highlights = annotationStore.allHighlights.first(),
            words = annotationStore.allWords.first(),
            exportedOn = day,
        )
        return SettingsFileExport(fileName = AnnotationMarkdown.fileName(null, day), mimeType = "text/markdown", text = text)
    }

    suspend fun backUp(): SettingsFileExport {
        val file = annotationBackupStore.backUp()
        return SettingsFileExport(fileName = file.fileName, mimeType = "application/json", text = file.text)
    }

    /** Adds what the backup holds and says what it added; throws when [text] isn't a Katana backup. */
    suspend fun restore(text: String): String = annotationBackupStore.restore(text).message

    private suspend fun scan(measureCovers: Boolean) = scanMutex.withLock {
        val candidates = keptBookCandidates()
        val free = withContext(Dispatchers.IO) { context.filesDir.usableSpace }
        val books = withContext(Dispatchers.IO) { keptBookFiles.measure(candidates) }
        // A remembered copy that is gone (removed here, or cleared by the system) is forgotten.
        remoteDocumentReadCache.forgetKept(remoteDocumentReadCache.keptItemIds() - books.mapTo(mutableSetOf()) { it.item.id })
        keptState.value = KeptBooksSnapshot(books = books, freeBytes = free, isLoaded = true)
        if (measureCovers) coverBytes.value = withContext(Dispatchers.IO) { coverCacheBytes(context) }
        val imported = withContext(Dispatchers.IO) { importedBookBytes(filesDir = context.filesDir, cacheDir = context.cacheDir) }
        storageState.value = StorageSettingsState(
            downloadedBookBytes = books.filter { it.isDownload }.sumOf { it.sizeBytes },
            keptBookBytes = books.filterNot { it.isDownload }.sumOf { it.sizeBytes },
            coverBytes = coverBytes.value ?: 0L,
            importedBookBytes = imported,
            freeBytes = free,
            finishedBookCount = books.count { it.isFinished },
            isLoaded = true,
        )
    }

    /**
     * Every server book read on this device, downloaded to it, or opened here without being read (its copy
     * kept all the same): those that may have a file here.
     */
    private suspend fun keptBookCandidates(): List<KeptBookCandidate> {
        readerSessionStore.isHydratedFlow.first { hydrated -> hydrated }
        val progress = readerSessionStore.progressByItemIdFlow.value
        val downloaded = downloadQueue.observeEntries().first().entries
            .filter { entry -> entry.status == DownloadEntryStatus.Completed }
            .flatMapTo(mutableSetOf()) { entry -> entry.completedItemIds }
        val openedOnly = withContext(Dispatchers.IO) { remoteDocumentReadCache.keptItemIds() }
        val items = readableItemLookup.getItemsByIds((progress.keys + downloaded + openedOnly).toList())
        return items
            .filter { item -> !item.isLocalImport && downloadDataSources.downloadDataSourceFor(item) != null }
            .map { item ->
                keptBookCandidate(
                    item = item,
                    progress = progress[item.id],
                    isDownload = item.id in downloaded,
                    keptSinceEpochMillis = withContext(Dispatchers.IO) { remoteDocumentReadCache.keptSinceEpochMillis(item) },
                )
            }
    }

}

/**
 * One book that may have a file here. A book opened but never read has no progress; [keptSinceEpochMillis]
 * (when its copy was written) stands for when it was opened, so it reads "opened today", not "not opened yet".
 */
internal fun keptBookCandidate(
    item: ReadableItem,
    progress: ReaderProgressSnapshot?,
    isDownload: Boolean,
    keptSinceEpochMillis: Long? = null,
): KeptBookCandidate {
    val percent = progress?.percent?.coerceIn(0.0, 1.0)
    return KeptBookCandidate(
        item = item,
        openedAtEpochMillis = progress?.updatedAtEpochMillis ?: keptSinceEpochMillis.takeUnless { isDownload },
        readPercent = percent?.let { (it * PERCENT).roundToInt() },
        isFinished = progress != null && (progress.isFinished || (percent ?: 0.0) >= 1.0),
        isDownload = isDownload,
    )
}

/**
 * The servers' covers kept for the library (their cover folders, in the app's files and its
 * cache), which Clear covers removes; an imported book's cover is not one of them.
 */
internal fun coverCacheBytes(context: Context): Long =
    listOf(context.filesDir, context.cacheDir).sumOf { root ->
        root.listFiles().orEmpty()
            .filter { file -> file.isDirectory && ServerCoverFolders.any(file.name::startsWith) }
            .sumOf { folder -> folder.walkTopDown().filter(File::isFile).sumOf(File::length) }
    }

/**
 * What the books imported from this device keep in the app's own space: their covers, the EPUB a MOBI, AZW, AZW3
 * or FB2 is read from, a DjVu's copy and a comic's copy. The picked files themselves stay where they are.
 */
internal fun importedBookBytes(filesDir: File, cacheDir: File): Long =
    (ImportedBookFolders.map { name -> File(filesDir, name) } + ImportedBookCacheFolders.map { name -> File(cacheDir, name) })
        .filter(File::isDirectory)
        .sumOf { folder -> folder.walkTopDown().filter(File::isFile).sumOf(File::length) }

private const val PERCENT = 100
private val ImportedBookFolders = listOf("imported-cover-cache")
private val ImportedBookCacheFolders = listOf(
    "epub-imports",
    "djvu-imports",
    "comic-zip-archives",
    "comic-archives",
    "comic-archives-solid",
)
private val ServerCoverFolders = listOf(
    GRIMMORY_COVER_DIRECTORY_NAME,
    KAVITA_COVER_DIRECTORY_NAME,
    STUMP_COVER_DIRECTORY_NAME,
)
