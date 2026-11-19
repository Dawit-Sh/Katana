package com.example.unifiedreader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file being downloaded, kept apart from finished books: the bytes so far in [dataFile] and, in [infoFile],
 * what the server said about the whole file (its length and version marks). A download that broke off asks
 * next time for the rest only, and only while the server still has the same file.
 */
class PartialDownloadFile(
    val dataFile: File,
    val infoFile: File = File(dataFile.path + INFO_FILE_SUFFIX),
) {
    /** The bytes downloaded so far. */
    val downloadedBytes: Long
        get() = if (dataFile.isFile) dataFile.length() else 0L

    /** Forgets the download: its bytes and what was known about the file. */
    fun delete() {
        dataFile.delete()
        infoFile.delete()
    }

    internal fun readInfo(): PartialDownloadInfo? = try {
        infoFile.takeIf(File::isFile)?.let { file ->
            val properties = Properties().apply { file.inputStream().use(::load) }
            PartialDownloadInfo(
                totalBytes = properties.getProperty(TOTAL_BYTES_KEY)?.toLongOrNull(),
                entityTag = properties.getProperty(ENTITY_TAG_KEY),
                lastModified = properties.getProperty(LAST_MODIFIED_KEY),
            )
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // A half-written info file: start over.
        null
    }

    internal fun writeInfo(info: PartialDownloadInfo) {
        val properties = Properties()
        info.totalBytes?.let { total -> properties.setProperty(TOTAL_BYTES_KEY, total.toString()) }
        info.entityTag?.let { tag -> properties.setProperty(ENTITY_TAG_KEY, tag) }
        info.lastModified?.let { date -> properties.setProperty(LAST_MODIFIED_KEY, date) }
        infoFile.parentFile?.mkdirs()
        infoFile.outputStream().use { output -> properties.store(output, null) }
    }
}

/**
 * The body ended before the length the server gave: the connection broke off without saying so. A
 * [isConnectionFailure], so the download waits and goes on later.
 */
class IncompleteDownloadException(
    expectedBytes: Long,
    receivedBytes: Long,
) : IOException("The download stopped at $receivedBytes of $expectedBytes bytes.")

/** What the server said about the whole file: its length, and the version marks that tell a changed file. */
internal data class PartialDownloadInfo(
    val totalBytes: Long?,
    val entityTag: String?,
    val lastModified: String?,
) {
    /** What `If-Range` carries: the strong entity tag, else the modification date. */
    val validator: String?
        get() = entityTag ?: lastModified

    /** Whether [bytes] already downloaded can be the start of this file and a later answer can be checked against it. */
    fun canContinueFrom(bytes: Long): Boolean =
        bytes > 0L && (totalBytes == null || bytes <= totalBytes) && (validator != null || totalBytes != null)
}

/**
 * Downloads the file that [request] asks for into [target], going on after the bytes [target] already holds
 * when the server can send just the rest: a `Range` request answered with 206 Partial Content, for the same
 * file (same length and version marks). A server that sends the whole file instead (200), says the range is
 * wrong (416) or now has another file gets a fresh start, so a resumed file is never two files spliced.
 *
 * [throwIfError] turns an error answer into the source's own exception. Returns once [target]'s data file holds
 * the whole file; when the connection breaks off, it keeps what arrived for the next call. No total time
 * limit applies here: the client's socket timeout gives up only after a stretch with no bytes at all.
 */
suspend fun HttpClient.downloadResumably(
    target: PartialDownloadFile,
    request: HttpRequestBuilder.() -> Unit,
    throwIfError: suspend (HttpResponse) -> Unit,
) = withContext(Dispatchers.IO) {
    target.dataFile.parentFile?.mkdirs()
    val saved = target.readInfo()?.takeIf { info -> info.canContinueFrom(target.downloadedBytes) }
    val alreadyComplete = saved?.totalBytes != null && saved.totalBytes == target.downloadedBytes
    if (!alreadyComplete) {
        val continued = saved != null && downloadOnce(target, saved, request, throwIfError)
        if (!continued) {
            target.delete()
            downloadOnce(target, resumeFrom = null, request = request, throwIfError = throwIfError)
        }
    }
}

/**
 * One request. With [resumeFrom], asks for the bytes after those [target] holds and returns false when the
 * answer can't continue them (the caller then starts over); without, downloads the whole file.
 */
private suspend fun HttpClient.downloadOnce(
    target: PartialDownloadFile,
    resumeFrom: PartialDownloadInfo?,
    request: HttpRequestBuilder.() -> Unit,
    throwIfError: suspend (HttpResponse) -> Unit,
): Boolean {
    val offset = if (resumeFrom != null) target.downloadedBytes else 0L
    return prepareGet {
        request()
        if (resumeFrom != null) {
            header(HttpHeaders.Range, "bytes=$offset-")
            resumeFrom.validator?.let { validator -> header(HttpHeaders.IfRange, validator) }
        }
    }.execute { response ->
        when {
            resumeFrom != null && response.status == HttpStatusCode.PartialContent ->
                response.appendRestTo(target, resumeFrom, offset)
            resumeFrom != null && response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> false
            else -> {
                throwIfError(response)
                check(response.status != HttpStatusCode.PartialContent) { "The server sent part of the file only." }
                // 200: the whole file, from the start; a resumed download's bytes are replaced.
                val info = response.downloadInfo()
                target.writeInfo(info)
                response.writeBodyTo(target.dataFile, append = false)
                target.checkComplete(info.totalBytes)
                true
            }
        }
    }
}

/** Appends a 206 answer's bytes when they continue [target]'s file; false when they belong to another file. */
private suspend fun HttpResponse.appendRestTo(
    target: PartialDownloadFile,
    saved: PartialDownloadInfo,
    offset: Long,
): Boolean {
    val range = headers[HttpHeaders.ContentRange]?.let(::parseContentRange)
    val continues = range != null &&
        range.first == offset &&
        sameIfBothKnown(saved.totalBytes, range.totalBytes) &&
        sameIfBothKnown(saved.entityTag, headers[HttpHeaders.ETag]?.strongEntityTagOrNull()) &&
        sameIfBothKnown(saved.lastModified, headers[HttpHeaders.LastModified])
    if (continues) {
        val totalBytes = saved.totalBytes ?: range?.totalBytes
        if (totalBytes != saved.totalBytes) target.writeInfo(saved.copy(totalBytes = totalBytes))
        writeBodyTo(target.dataFile, append = true)
        target.checkComplete(totalBytes)
    }
    return continues
}

private fun HttpResponse.downloadInfo(): PartialDownloadInfo = PartialDownloadInfo(
    // OkHttp drops the length when it unzips a body it asked to be zipped; the length is then unknown.
    totalBytes = contentLength()?.takeIf { length -> length >= 0L },
    entityTag = headers[HttpHeaders.ETag]?.strongEntityTagOrNull(),
    lastModified = headers[HttpHeaders.LastModified]?.takeIf(String::isNotBlank),
)

private fun PartialDownloadFile.checkComplete(totalBytes: Long?) {
    if (totalBytes != null && downloadedBytes != totalBytes) {
        throw IncompleteDownloadException(expectedBytes = totalBytes, receivedBytes = downloadedBytes)
    }
}

private fun <T : Any> sameIfBothKnown(saved: T?, received: T?): Boolean =
    saved == null || received == null || saved == received

// A weak tag (W/"...") may not be used to continue a download (RFC 9110, If-Range).
private fun String.strongEntityTagOrNull(): String? = trim().takeIf { tag -> tag.isNotEmpty() && !tag.startsWith("W/") }

internal data class ContentRange(
    val first: Long,
    val last: Long,
    val totalBytes: Long?,
)

/** `bytes 100-999/1000`, with a star for a length the server doesn't know; null for anything else. */
internal fun parseContentRange(value: String): ContentRange? {
    val (first, last, total) = ContentRangePattern.matchEntire(value.trim())?.destructured ?: return null
    val firstByte = first.toLongOrNull()
    val lastByte = last.toLongOrNull()
    val totalBytes = if (total == "*") null else total.toLongOrNull() ?: -1L
    return if (firstByte != null && lastByte != null && totalBytes != -1L) {
        ContentRange(first = firstByte, last = lastByte, totalBytes = totalBytes)
    } else {
        null
    }
}

private val ContentRangePattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

private const val INFO_FILE_SUFFIX = ".info"
private const val TOTAL_BYTES_KEY = "totalBytes"
private const val ENTITY_TAG_KEY = "entityTag"
private const val LAST_MODIFIED_KEY = "lastModified"
