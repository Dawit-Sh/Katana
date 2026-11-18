package com.example.unifiedreader.ui.components

import android.os.Process
import android.os.SystemClock

/**
 * Marks on the way from a cold start to a library with real covers, each logged once per process with
 * the time since Android started the process:
 *
 *   adb logcat -s KatanaStartup
 *
 * The first real cover on screen counts as the library being ready: the activity reports it as fully
 * drawn ([onLibraryReady]), so `am start -W` and ActivityManager's "Fully drawn" line time it too.
 * Tolerates `android.util.Log` being missing (plain JVM unit tests).
 */
object AppStartupLog {
    private const val TAG = "KatanaStartup"
    private val logged = mutableSetOf<String>()
    private var libraryReady = false
    private var onLibraryReady: (() -> Unit)? = null

    /** Logs [name] the first time it happens in this process; later calls do nothing. */
    fun mark(name: String) {
        val first = synchronized(logged) { logged.add(name) }
        if (!first) return
        val sinceStart = runCatching { SystemClock.uptimeMillis() - Process.getStartUptimeMillis() }.getOrNull()
        runCatching { android.util.Log.i(TAG, "$name +${sinceStart ?: "?"} ms") }
    }

    /** A real cover is on screen: the library is ready to use. */
    fun coverShown() {
        mark("library.firstCover")
        val callback = synchronized(logged) {
            if (libraryReady) return
            libraryReady = true
            onLibraryReady.also { onLibraryReady = null }
        }
        callback?.invoke()
    }

    /** Runs [callback] (once) when the first real cover shows; at once if one already has. */
    fun onLibraryReady(callback: () -> Unit) {
        val runNow = synchronized(logged) {
            if (libraryReady) true else {
                onLibraryReady = callback
                false
            }
        }
        if (runNow) callback()
    }
}
