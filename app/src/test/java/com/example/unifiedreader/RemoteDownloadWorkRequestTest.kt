package com.example.unifiedreader

import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteDownloadWorkRequestTest {
    @Test
    fun `queued downloads are expedited only where Android runs them as jobs`() {
        // Android 9-11 would run expedited work as a foreground service, which the worker can't start.
        assertFalse(remoteDownloadWorkRequest("entry", Build.VERSION_CODES.P).workSpec.expedited)
        assertFalse(remoteDownloadWorkRequest("entry", Build.VERSION_CODES.R).workSpec.expedited)
        assertTrue(remoteDownloadWorkRequest("entry", Build.VERSION_CODES.S).workSpec.expedited)
        assertTrue(remoteDownloadWorkRequest("entry", Build.VERSION_CODES.BAKLAVA).workSpec.expedited)
    }

    @Test
    fun `a queued download waits for a network and knows its entry`() {
        val request = remoteDownloadWorkRequest("entry-7", Build.VERSION_CODES.P)

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals("entry-7", request.workSpec.input.getString(RemoteDownloadWorker.EntryIdKey))
    }

    @Test
    fun `a download that lost its connection is tried again every half minute more, not doubling`() {
        listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.BAKLAVA).forEach { sdkInt ->
            val workSpec = remoteDownloadWorkRequest("entry", sdkInt).workSpec

            assertEquals(BackoffPolicy.LINEAR, workSpec.backoffPolicy)
            assertEquals(30_000L, workSpec.backoffDelayDuration)
        }
    }
}
