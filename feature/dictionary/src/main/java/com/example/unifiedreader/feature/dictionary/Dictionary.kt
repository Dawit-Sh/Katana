package com.example.unifiedreader.feature.dictionary

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.core.model.toLowerHex
import com.example.unifiedreader.core.network.PartialDownloadFile
import com.example.unifiedreader.core.network.downloadResumably
import com.example.unifiedreader.core.network.isConnectionFailure
import com.example.unifiedreader.feature.annotations.WordMeanings
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The pack the app downloads: where it is published, its size and its SHA-256. */
data class DictionaryPackInfo(
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileName: String,
) {
    companion object {
        /** Open English WordNet 2025, built by tools/build-dictionary.py, on the dict-en-1 release. */
        val English = DictionaryPackInfo(
            url = "https://github.com/Dawit-Sh/Katana/releases/download/dict-en-1/katana-dictionary-en-1.sqlite",
            sizeBytes = 17_588_224L,
            sha256 = "a43de9199fa07fa0b936ff4f24298887a29d34ba08cd73629fd2e8076f59b8e3",
            fileName = "katana-dictionary-en-1.sqlite",
        )
    }
}

/** Where the English dictionary stands on this device. */
sealed interface DictionaryInstall {
    data object NotInstalled : DictionaryInstall

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DictionaryInstall

    data class Installed(val sizeBytes: Long) : DictionaryInstall

    /** The last download failed; [message] says why, and Download tries again (going on from where it stopped). */
    data class Failed(val message: String) : DictionaryInstall
}

/**
 * The offline English dictionary: downloaded on request into app storage, checked against its
 * SHA-256, then read from this device only. Nothing is built into the APK.
 */
class Dictionary(
    private val directory: File,
    private val scope: CoroutineScope,
    private val download: suspend (target: PartialDownloadFile, url: String) -> Unit,
    private val pack: DictionaryPackInfo = DictionaryPackInfo.English,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openPack: (File) -> DictionarySource = ::SqliteDictionaryPack,
) {
    private val packFile = File(directory, pack.fileName)
    private val partial = PartialDownloadFile(File(directory, pack.fileName + PART_SUFFIX))
    private val _state = MutableStateFlow(initialState())
    private val openLock = Mutex()
    private var open: DictionarySource? = null
    private var downloadJob: Job? = null

    val state: StateFlow<DictionaryInstall> = _state.asStateFlow()

    /** The pack's size, for "Download the English dictionary (17 MB)". */
    val downloadSizeBytes: Long
        get() = pack.sizeBytes

    /** Starts (or goes on with) the download; nothing when one runs or the pack is here. */
    fun install() {
        if (downloadJob?.isActive == true || packFile.isFile) return
        downloadJob = scope.launch {
            _state.value = DictionaryInstall.Downloading(partial.downloadedBytes, pack.sizeBytes)
            val progress = launch {
                while (true) {
                    delay(PROGRESS_INTERVAL_MILLIS)
                    _state.value = DictionaryInstall.Downloading(partial.downloadedBytes, pack.sizeBytes)
                }
            }
            val result = runSuspendCatching {
                download(partial, pack.url)
                withContext(ioDispatcher) { finishDownload() }
            }
            progress.cancel()
            _state.value = result.fold(
                onSuccess = { it },
                onFailure = { failure -> DictionaryInstall.Failed(failureMessage(failure)) },
            )
        }
    }

    /** Removes the pack from this device; a later lookup says to download it again. */
    suspend fun remove() {
        downloadJob?.cancel()
        openLock.withLock {
            open?.close()
            open = null
        }
        withContext(ioDispatcher) {
            packFile.delete()
            partial.delete()
        }
        _state.value = DictionaryInstall.NotInstalled
    }

    /** Looks a selected word up off the main thread. */
    suspend fun lookUp(word: String): DictionaryLookup = withContext(ioDispatcher) {
        val source = openLock.withLock {
            open ?: if (packFile.isFile) openPack(packFile).also { open = it } else null
        } ?: return@withContext DictionaryLookup.NotInstalled
        source.lookUp(word)?.let(DictionaryLookup::Found)
            ?: DictionaryLookup.NotFound(WordForms.normalize(word).ifEmpty { word.trim() })
    }

    private fun finishDownload(): DictionaryInstall {
        val digest = MessageDigest.getInstance("SHA-256")
        partial.dataFile.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (!digest.digest().toLowerHex().equals(pack.sha256, ignoreCase = true)) {
            partial.delete()
            throw DamagedPackException()
        }
        if (!partial.dataFile.renameTo(packFile)) throw IOException("The dictionary couldn't be saved on this device.")
        partial.infoFile.delete()
        return DictionaryInstall.Installed(packFile.length())
    }

    private fun initialState(): DictionaryInstall =
        if (packFile.isFile) DictionaryInstall.Installed(packFile.length()) else DictionaryInstall.NotInstalled

    private class DamagedPackException : IOException("The download was damaged.")

    private fun failureMessage(failure: Throwable): String = when {
        failure is DamagedPackException -> "The download was damaged. Try again."
        failure.isConnectionFailure() -> "The connection dropped. Download goes on from where it stopped."
        else -> "The download failed. Try again."
    }

    companion object {
        /** The app's dictionary, downloading with [httpClient] (which follows GitHub's redirect). */
        fun create(directory: File, scope: CoroutineScope, httpClient: HttpClient): Dictionary = Dictionary(
            directory = directory,
            scope = scope,
            download = { target, address ->
                httpClient.downloadResumably(
                    target = target,
                    request = { url(address) },
                    throwIfError = { response ->
                        if (!response.status.isSuccess()) throw IOException("The download answered ${response.status.value}")
                    },
                )
            },
        )

        private const val PART_SUFFIX = ".part"
        private const val PROGRESS_INTERVAL_MILLIS = 250L
        private const val HASH_BUFFER_SIZE = 64 * 1024
    }
}

/** My words' meanings from the dictionary: the first definition, English only. */
class DictionaryWordMeanings(private val dictionary: Dictionary) : WordMeanings {
    override suspend fun meaning(word: String, language: String): String? {
        if (!language.startsWith("en", ignoreCase = true)) return null
        return (dictionary.lookUp(word) as? DictionaryLookup.Found)?.entry?.firstDefinition
    }
}

/** A saved word's own stored meaning when it has one, else what [meanings] finds. */
suspend fun WordMeanings.meaningOf(word: String, stored: String?, language: String): String? =
    stored?.trim()?.takeIf(String::isNotEmpty) ?: meaning(word, language)

/** The app's dictionary, for the readers' Dictionary card and Settings › Reading. */
val LocalDictionary = staticCompositionLocalOf<Dictionary?> { null }

/** Opens Settings, where the dictionary is downloaded; null where the screen can't go there. */
val LocalOpenDictionarySettings = staticCompositionLocalOf<(() -> Unit)?> { null }
