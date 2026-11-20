package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.example.unifiedreader.core.model.runSuspendCatching
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where books on this device come from besides Add books (2026-11-20, for friends who only read their own
 * files): folders picked once and read where they are, and, with "Find books on this phone", the whole
 * phone's storage. Kept on this device only.
 */
class LocalBookPlaces(context: Context) {
    private val prefs = context.getSharedPreferences("local_book_places", Context.MODE_PRIVATE)

    val folders: List<Uri>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().sorted().map(Uri::parse)

    var findsOnWholePhone: Boolean
        get() = prefs.getBoolean(KEY_WHOLE_PHONE, false)
        set(value) = prefs.edit().putBoolean(KEY_WHOLE_PHONE, value).apply()

    fun addFolder(tree: Uri) = prefs.edit().putStringSet(KEY_FOLDERS, folderStrings() + tree.toString()).apply()

    fun removeFolder(tree: Uri) = prefs.edit().putStringSet(KEY_FOLDERS, folderStrings() - tree.toString()).apply()

    val hasPlaces: Boolean get() = findsOnWholePhone || folders.isNotEmpty()

    private fun folderStrings(): Set<String> = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toSet()

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val KEY_WHOLE_PHONE = "whole_phone"
    }
}

/** Whether Katana may read every file on the phone: "All files access" from Android 11, storage before. */
fun hasWholePhoneAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    Environment.isExternalStorageManager()
} else {
    context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** What a look through the places found: the books, and the places it could read in full. */
data class LocalBookSearch(
    val books: List<Uri>,
    // A place read in full: a book of it not found is gone. A place it couldn't read removes nothing.
    val readPlaces: List<LocalBookPlace>,
)

sealed interface LocalBookPlace {
    data class Folder(val tree: Uri) : LocalBookPlace
    data object WholePhone : LocalBookPlace

    /** Whether the book at [book] (as Katana keeps it) comes from this place. */
    fun holds(book: Uri): Boolean = when (this) {
        is Folder -> book.scheme == "content" && book.authority == tree.authority &&
            book.toString().startsWith(tree.toString() + "/document/")
        WholePhone -> book.scheme == "file"
    }
}

/** Finds the books in the folders picked and, when on, on the whole phone. */
class LocalBookFinder(private val context: Context) {
    suspend fun search(places: LocalBookPlaces): LocalBookSearch = withContext(Dispatchers.IO) {
        val books = mutableListOf<Uri>()
        val read = mutableListOf<LocalBookPlace>()
        // The whole phone holds the folders too: searched once, so no book comes in twice.
        val wholePhone = places.findsOnWholePhone && hasWholePhoneAccess(context)
        if (!wholePhone) places.folders.forEach { tree ->
            runSuspendCatching { folderBooks(tree) }.onSuccess { found ->
                books += found
                read += LocalBookPlace.Folder(tree)
            }
        }
        if (wholePhone) {
            runSuspendCatching { phoneBooks() }.onSuccess { found ->
                books += found
                read += LocalBookPlace.WholePhone
            }
        }
        LocalBookSearch(books = books.distinct(), readPlaces = read)
    }

    /** The file's name and size, to know a book already in the library under another address. */
    fun fingerprint(book: Uri): String? = runCatching {
        if (book.scheme == "file") {
            val file = File(requireNotNull(book.path))
            "${file.name}|${file.length()}"
        } else {
            context.contentResolver.query(
                book,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) "${cursor.getString(0)}|${cursor.getLong(1)}" else null }
        }
    }.getOrNull()

    // Every book file under a picked folder, through the folder's own permission.
    private fun folderBooks(tree: Uri): List<Uri> {
        val found = mutableListOf<Uri>()
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_FOLDERS) {
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: error("Can't read $tree")
            cursor.use { rows -> readFolderRows(rows, tree, pending, found) }
        }
        return found
    }

    // One folder's rows: its folders to look in next (not hidden ones), and its book files.
    private fun readFolderRows(
        rows: android.database.Cursor,
        tree: Uri,
        pending: ArrayDeque<String>,
        found: MutableList<Uri>,
    ) {
        while (rows.moveToNext()) {
            val id = rows.getString(0) ?: continue
            val name = rows.getString(1).orEmpty()
            val isFolder = rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
            if (isFolder && !name.startsWith(".")) pending += id
            if (!isFolder && name.isBookFileName()) found += DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
    }

    // Every book file in the phone's storage, past Android's own folders and hidden ones.
    private fun phoneBooks(): List<Uri> {
        val root = Environment.getExternalStorageDirectory()
        if (!root.canRead()) error("Can't read the phone's storage")
        return root.walkTopDown()
            .onEnter { dir ->
                dir == root || (!dir.name.startsWith(".") && !(dir.parentFile == root && dir.name == "Android"))
            }
            .maxDepth(MAX_DEPTH)
            .filter { file -> file.isFile && file.name.isBookFileName() && file.length() > 0 }
            .take(MAX_BOOKS)
            .map(Uri::fromFile)
            .toList()
    }

    private companion object {
        const val MAX_FOLDERS = 5_000
        const val MAX_DEPTH = 12
        const val MAX_BOOKS = 5_000
    }
}

internal fun String.isBookFileName(): Boolean = substringAfterLast('.', "").lowercase() in BookFileExtensions

private val BookFileExtensions = setOf("epub", "pdf", "djvu", "djv", "cbz", "cbr", "mobi", "azw", "azw3", "fb2")

/** How a picked folder reads in Book folders: "Download/Books" on the phone, "SD card · Books" on a card. */
internal fun folderLabelOf(treeDocumentId: String): String {
    val volume = treeDocumentId.substringBefore(':')
    val path = treeDocumentId.substringAfter(':', "").trim('/')
    return when {
        volume == "primary" -> path.ifEmpty { "The phone's storage" }
        // Picked under Downloads on older Android (the Fire): a file path, "raw:/storage/emulated/0/Download/…".
        volume == "raw" -> storagePathLabel(path)
        treeDocumentId == "downloads" -> "Download"
        !SdCardVolume.matches(volume) -> path.ifEmpty { treeDocumentId }
        path.isEmpty() -> "SD card"
        else -> "SD card · $path"
    }
}

// "storage/emulated/0/Download/Books" → "Download/Books"; "storage/1A2B-3C4D/Books" → "SD card · Books".
private fun storagePathLabel(path: String): String {
    val parts = path.split('/').filter(String::isNotEmpty)
    val onPhone = parts.take(PHONE_STORAGE_PARTS).let { it.size == PHONE_STORAGE_PARTS && it[0] == "storage" && it[1] == "emulated" }
    val onCard = parts.size >= CARD_STORAGE_PARTS && parts[0] == "storage" && SdCardVolume.matches(parts[1])
    return when {
        onPhone -> parts.drop(PHONE_STORAGE_PARTS).joinToString("/").ifEmpty { "The phone's storage" }
        onCard -> parts.drop(CARD_STORAGE_PARTS).joinToString("/").let { rest -> if (rest.isEmpty()) "SD card" else "SD card · $rest" }
        else -> path
    }
}

// "storage/emulated/0" before a path on the phone, "storage/1A2B-3C4D" before one on a card.
private const val PHONE_STORAGE_PARTS = 3
private const val CARD_STORAGE_PARTS = 2

// An SD card's volume id, "1A2B-3C4D".
private val SdCardVolume = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

fun folderLabel(tree: Uri): String =
    runCatching { folderLabelOf(DocumentsContract.getTreeDocumentId(tree)) }.getOrNull() ?: tree.lastPathSegment.orEmpty()

/** What a refresh of the places changed: books added, and books gone from the phone. */
data class LocalBookRefresh(val added: Int, val removed: Int)

/**
 * Brings the places' books into the library: new files are added the way Add books adds one (read where they
 * are), and a book from a place read in full whose file is gone is taken out. Its highlights, bookmarks and
 * place stay, and come back with the file. A place that couldn't be read takes nothing out.
 */
class LocalBookRefresher(
    private val places: LocalBookPlaces,
    private val finder: LocalBookFinder,
    private val library: LocalDocumentDataSource,
) {
    private val running = Mutex()

    suspend fun refresh(): LocalBookRefresh = running.withLock {
        if (!places.hasPlaces) return@withLock LocalBookRefresh(added = 0, removed = 0)
        val search = finder.search(places)
        val known = library.getImportedItems().mapNotNull { item -> library.getDocumentUri(item)?.let { uri -> item.id to uri } }
        val knownUris = known.mapTo(mutableSetOf()) { (_, uri) -> uri.toString() }
        // The same file added by hand, or found before under another address, isn't added again.
        val knownFiles = known.mapNotNullTo(mutableSetOf()) { (_, uri) -> finder.fingerprint(uri) }
        val added = search.books.filterNot { book -> book.toString() in knownUris }.count { book ->
            val file = finder.fingerprint(book)
            if (file != null && file in knownFiles) return@count false
            val imported = runSuspendCatching { library.import(book) }.getOrNull() != null
            if (imported && file != null) knownFiles += file
            imported
        }
        val found = search.books.mapTo(mutableSetOf(), Uri::toString)
        val removed = known.count { (itemId, uri) ->
            uri.toString() !in found && search.readPlaces.any { place -> place.holds(uri) } && library.removeById(itemId)
        }
        LocalBookRefresh(added = added, removed = removed)
    }

    /**
     * Takes a place out of Book folders: Katana stops looking there, and its books leave the library. The files
     * stay on the phone, and their highlights, bookmarks and places come back with the folder. Returns how many
     * books left.
     */
    suspend fun forget(place: LocalBookPlace): Int = running.withLock {
        when (place) {
            is LocalBookPlace.Folder -> places.removeFolder(place.tree)
            LocalBookPlace.WholePhone -> places.findsOnWholePhone = false
        }
        library.getImportedItems().count { item ->
            library.getDocumentUri(item)?.let(place::holds) == true && library.removeById(item.id)
        }
    }
}
