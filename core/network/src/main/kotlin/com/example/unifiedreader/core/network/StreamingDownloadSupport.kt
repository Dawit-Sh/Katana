package com.example.unifiedreader.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream

/** Writes the answer's body to [destination], after what is there when [append]. */
suspend fun HttpResponse.writeBodyTo(
    destination: File,
    append: Boolean = false,
) {
    val channel = bodyAsChannel()
    val buffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
    FileOutputStream(destination, append).use { output ->
        while (true) {
            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
            if (bytesRead == -1) break
            if (bytesRead > 0) {
                output.write(buffer, 0, bytesRead)
            }
        }
    }
}

private const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 8 * 1024
