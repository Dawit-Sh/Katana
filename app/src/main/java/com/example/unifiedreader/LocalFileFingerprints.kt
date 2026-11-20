package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.model.PartialMd5
import com.example.unifiedreader.core.model.runSuspendCatching
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A book file's size and KOReader-style partial MD5 ([PartialMd5]): the same file whichever folder or app it was
 * picked from, where the picker's address (and so the book's id) differs.
 */
data class LocalFileFingerprint(val size: Long, val partialMd5: String)

/** The fingerprint of the file behind a content address, or null when it can't be read. */
fun interface LocalFileFingerprinter {
    suspend fun of(uri: String): LocalFileFingerprint?
}

/** Fingerprints of the files behind content addresses, each read once (12 KB at most) and kept while the app runs. */
class LocalFileFingerprints(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalFileFingerprinter {
    private val byUri = ConcurrentHashMap<String, LocalFileFingerprint>()

    /** [uri]'s fingerprint, or null when its file can't be read (gone, permission lost, not seekable). */
    override suspend fun of(uri: String): LocalFileFingerprint? = byUri[uri] ?: withContext(ioDispatcher) {
        runSuspendCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream -> fingerprintOf(stream.channel) }
            }
        }.getOrNull()?.also { fingerprint -> byUri[uri] = fingerprint }
    }
}

/** [channel]'s file fingerprinted the way KOReader and Grimmory do it. */
internal fun fingerprintOf(channel: FileChannel): LocalFileFingerprint {
    val size = channel.size()
    val md5 = PartialMd5.of(size) { start, length ->
        val buffer = ByteBuffer.allocate(length)
        var at = start
        var read = 0
        while (buffer.hasRemaining() && read >= 0) {
            read = channel.read(buffer, at)
            at += read.coerceAtLeast(0)
        }
        buffer.array()
    }
    return LocalFileFingerprint(size = size, partialMd5 = md5)
}
