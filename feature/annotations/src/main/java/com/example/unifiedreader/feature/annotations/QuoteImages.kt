package com.example.unifiedreader.feature.annotations

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sharing and saving a quote card: the share sheet, and Pictures/Katana through the media store. */
object QuoteImages {
    private const val FOLDER = "Katana"
    private const val CACHE_FOLDER = "quotes"
    private const val PNG = "image/png"
    private const val PNG_QUALITY = 100

    /** The card's file name: the book's title and the moment, safe on any file system. */
    fun fileName(title: String, atEpochMillis: Long): String {
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").oneLine().take(MAX_NAME).ifEmpty { "Quote" }
        return "$name $atEpochMillis.png"
    }

    /**
     * Opens Android's share sheet with the card. False when the card couldn't be written or nothing
     * on the tablet shares images.
     */
    suspend fun share(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            try {
                val folder = File(context.cacheDir, CACHE_FOLDER).apply { mkdirs() }
                // Only the latest card is kept; the share sheet has read the earlier ones by now.
                folder.listFiles()?.forEach(File::delete)
                File(folder, fileName).also { file ->
                    file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream) }
                }
            } catch (_: IOException) {
                null
            }
        } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.quotes", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Whether saving needs the storage permission first (Android 9 and older, until it is given). */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    const val PERMISSION: String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** Saves the card to Pictures/Katana, where the gallery finds it. False when it couldn't. */
    suspend fun save(context: Context, bitmap: Bitmap, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, PNG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
                if (!folder.isDirectory && !folder.mkdirs()) return@withContext false
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(folder, fileName).absolutePath)
            }
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withContext false
        val written = try {
            resolver.openOutputStream(uri)?.use { stream: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, stream)
            } == true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return@withContext false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        true
    }

    /**
     * The picture at [uri] (from the photo picker) as a card's background: turned upright and read no
     * larger than a card needs. Null when it can't be read (gone, not a picture, or no longer allowed).
     */
    suspend fun loadPhoto(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // A software bitmap, so the card can be drawn from it off the screen.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(QuoteCardBackdrops.photoSampleSize(info.size.width, info.size.height))
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val MAX_NAME = 60
}
