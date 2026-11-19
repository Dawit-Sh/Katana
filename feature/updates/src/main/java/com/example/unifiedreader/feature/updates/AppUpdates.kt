package com.example.unifiedreader.feature.updates

import android.content.Context
import android.os.Build
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

/** Builds the app's updater. Construction only: nothing is read or fetched until it starts. */
object AppUpdates {
    fun createController(
        context: Context,
        httpClient: HttpClient,
        applicationScope: CoroutineScope,
    ): AppUpdateController {
        val appContext = context.applicationContext
        return AppUpdateController(
            // The download site's latest.json first (no API limit); GitHub's API when it can't be read.
            releaseSource = FallbackReleaseSource(
                primary = SiteReleaseSource(httpClient, supportedAbis = Build.SUPPORTED_ABIS.toList()),
                fallback = GitHubReleaseSource(httpClient, supportedAbis = Build.SUPPORTED_ABIS.toList()),
            ),
            downloader = KtorAppUpdateDownloader(httpClient),
            packageInspector = PackageManagerAppPackageInspector(appContext),
            installer = PackageInstallerAppUpdateInstaller(appContext),
            preferencesStore = DataStoreAppUpdatePreferencesStore(appContext),
            downloadDirectory = { File(appContext.cacheDir, DOWNLOAD_DIRECTORY_NAME) },
            // State changes on the main thread; file and network work hop to IO.
            scope = applicationScope + Dispatchers.Main.immediate,
            ioDispatcher = Dispatchers.IO,
        )
    }

    private const val DOWNLOAD_DIRECTORY_NAME = "updates"
}
