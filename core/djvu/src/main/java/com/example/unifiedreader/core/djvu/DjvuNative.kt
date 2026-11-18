package com.example.unifiedreader.core.djvu

import android.graphics.Bitmap

/**
 * The JNI functions of libkatana_djvu.so (src/main/cpp/djvu_jni.cpp). The library loads the first time this
 * object is used. A handle is a native pointer; [DjvuDocument] owns it and serializes every call on it.
 */
internal object DjvuNative {
    private const val LIBRARY = "katana_djvu"

    private val loadError: UnsatisfiedLinkError? = try {
        System.loadLibrary(LIBRARY)
        null
    } catch (e: UnsatisfiedLinkError) {
        e
    }

    /** Throws [DjvuException] when the native library is missing, for example on an ABI this build skips. */
    fun ensureLoaded() {
        loadError?.let { throw DjvuException("DjVu support is missing from this build of the app", it) }
    }

    /** Opens the document at a UTF-8 path and returns its handle. Throws [DjvuException]. */
    @JvmStatic
    external fun nativeOpen(utf8Path: ByteArray): Long

    @JvmStatic
    external fun nativePageCount(handle: Long): Int

    /** Returns width, height and dpi of a page, rotation applied. Throws [DjvuException]. */
    @JvmStatic
    external fun nativePageInfo(handle: Long, pageIndex: Int): IntArray

    /** Draws a page over the whole ARGB_8888 bitmap. Throws [DjvuException]. */
    @JvmStatic
    external fun nativeRenderPage(handle: Long, pageIndex: Int, bitmap: Bitmap)

    @JvmStatic
    external fun nativeClose(handle: Long)
}
