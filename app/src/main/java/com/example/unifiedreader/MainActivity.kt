package com.example.unifiedreader

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.example.unifiedreader.core.model.DeviceClock
import com.example.unifiedreader.feature.library.LocalLibraryBookDescriptionLoader
import com.example.unifiedreader.feature.reader.epub.discardRestoredEpubNavigators
import com.example.unifiedreader.feature.reader.epub.installEpubNavigatorRestoreFactory
import com.example.unifiedreader.ui.components.AppLaunchSplashHold
import com.example.unifiedreader.ui.components.AppStartupLog
import com.example.unifiedreader.ui.components.LocalAppLaunchSplashHold
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var bookDescriptions: BookDescriptionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        AppStartupLog.mark("activity.onCreate")
        val splashScreen = installSplashScreen()
        installEpubNavigatorRestoreFactory()
        super.onCreate(savedInstanceState)
        discardRestoredEpubNavigators()
        DeviceClock.uses24Hour = android.text.format.DateFormat.is24HourFormat(this)
        // A fresh start keeps the splash up while the first screen is still placeholders; a
        // recreated activity (a rotation) has no splash to keep.
        val launchHold = if (savedInstanceState == null) {
            AppLaunchSplashHold(onReleased = ::logLaunchSplashReleased).also { hold ->
                splashScreen.setKeepOnScreenCondition(hold::shouldKeep)
            }.also {
                // The first real cover on screen: Android logs the cold start as fully drawn.
                AppStartupLog.onLibraryReady { runCatching { reportFullyDrawn() } }
            }
        } else {
            null
        }
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(
                LocalAppLaunchSplashHold provides launchHold,
                LocalLibraryBookDescriptionLoader provides bookDescriptions,
            ) {
                UnifiedReaderRoute()
            }
        }
        AppStartupLog.mark("activity.setContent")
    }

    // Follows a change to the 24-hour clock setting made while Katana was in the background.
    override fun onResume() {
        super.onResume()
        DeviceClock.uses24Hour = android.text.format.DateFormat.is24HourFormat(this)
    }
}

private fun logLaunchSplashReleased(heldMillis: Long, timedOut: Boolean) {
    AppStartupLog.mark("splash.released")
    Log.d("LaunchSplash", "kept ${heldMillis} ms past the first frame" + if (timedOut) ", stopped waiting" else "")
}
