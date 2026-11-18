package com.example.unifiedreader

import android.app.Application
import com.example.unifiedreader.feature.settings.loadStartupSettingsPreferences
import com.example.unifiedreader.ui.components.AppStartupLog
import com.example.unifiedreader.ui.theme.applyAndroidThemeMode
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class UnifiedReaderApp : Application() {
    override fun onCreate() {
        AppStartupLog.mark("app.onCreate")
        super.onCreate()
        val themeMode = loadStartupSettingsPreferences(this).themeMode
        applyAndroidThemeMode(themeMode)
        AppStartupLog.mark("app.onCreate.done")
    }
}
