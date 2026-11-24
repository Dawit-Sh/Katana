package com.example.unifiedreader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.library.LibraryLocalBookItems
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage

/** Add a folder and Find books on this device, for the library's "•••" (null where there's no library). */
class LocalBookActions(
    val onAddFolder: () -> Unit,
    val onFindOnPhone: () -> Unit,
    val findsOnPhone: Boolean,
    val onCheckForNewBooks: () -> Unit,
    val hasPlaces: Boolean = false,
    val onBookFolders: () -> Unit = {},
) {
    /** Sync with Katana Desk, in the same menu. */
    var onKatanaDesk: () -> Unit = {}
}

val LocalLocalBookActions = staticCompositionLocalOf<LocalBookActions?> { null }

/** The library "•••" items: Check for new books and Book folders once there is a folder or the phone search. */
internal fun LocalBookActions.menuItems(): LibraryLocalBookItems = LibraryLocalBookItems(
    onAddFolder = onAddFolder,
    onFindOnPhone = onFindOnPhone.takeUnless { findsOnPhone },
    onCheckForNewBooks = onCheckForNewBooks.takeIf { hasPlaces },
    onBookFolders = onBookFolders.takeIf { hasPlaces },
    onKatanaDesk = onKatanaDesk,
)

/**
 * The folder picker, the permission Find books on this device needs (All files access from Android 11, asked
 * in the system's own screen; storage before), Book folders, and a look through the folders whenever the Local
 * library shows. What changed shows as a toast.
 */
@Composable
internal fun rememberLocalBookActions(
    localBooks: AppLocalBooksViewModel,
    viewModel: AppViewModel,
    katanaDesk: KatanaDeskViewModel = hiltViewModel(),
): LocalBookActions {
    val context = LocalContext.current
    val toasts = viewModel.toasts
    val report: (LocalBookRefresh, Boolean) -> Unit = { result, asked ->
        result.toastText(asked)?.let { text -> toasts.show(AppToastMessage(text = text, icon = AppToastIcon.Added)) }
    }
    val toastRemoved: (Int) -> Unit = { removed ->
        toasts.show(AppToastMessage(text = removedToastText(removed), icon = AppToastIcon.Removed))
    }
    val findOnPhone = { localBooks.findOnWholePhone { result -> report(result, true) } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) localBooks.addFolder(tree) { result -> report(result, true) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) findOnPhone()
    }
    // Back from the system's All files access screen: on, if it was allowed there.
    var waitingForAllFilesAccess by rememberSaveable { mutableStateOf(false) }
    OnResume(enabled = waitingForAllFilesAccess) {
        waitingForAllFilesAccess = false
        if (hasWholePhoneAccess(context)) findOnPhone()
    }
    val state = viewModel.uiState
    WhenLocalLibraryShows(source = state.selectedSource, readerOpen = state.activeReader != null) {
        localBooks.refreshIfDue { result -> report(result, false) }
    }
    var showingBookFolders by rememberSaveable { mutableStateOf(false) }
    if (showingBookFolders) {
        BookFoldersDialog(
            places = localBooks.shownPlaces,
            onRemoveFolder = { tree -> localBooks.removeFolder(tree, toastRemoved) },
            onStopPhone = { localBooks.stopFindingOnPhone(toastRemoved) },
            onAddFolder = {
                showingBookFolders = false
                folderPicker.launch(null)
            },
            onDismiss = { showingBookFolders = false },
        )
    }
    return remember(localBooks, localBooks.shownPlaces) {
        LocalBookActions(
            onAddFolder = { folderPicker.launch(null) },
            onFindOnPhone = {
                when {
                    hasWholePhoneAccess(context) -> findOnPhone()
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        waitingForAllFilesAccess = true
                        openAllFilesAccess(context)
                    }
                    else -> storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
            findsOnPhone = localBooks.findsOnWholePhone,
            onCheckForNewBooks = { localBooks.refresh { result -> report(result, true) } },
            hasPlaces = localBooks.shownPlaces.any,
            onBookFolders = { showingBookFolders = true },
        ).apply { onKatanaDesk = katanaDesk::openSheet }
    }
}

// Android's All files access screen for Katana; the general list where a phone doesn't open it for one app.
private fun openAllFilesAccess(context: android.content.Context) {
    val forKatana = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(forKatana) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}

/** Calls [onResumed] when the app comes back to the front while [enabled]. */
@Composable
private fun OnResume(enabled: Boolean, onResumed: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnResumed by rememberUpdatedState(onResumed)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestEnabled) latestOnResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Calls [onShown] each time the Local library shows (no book open). */
@Composable
private fun WhenLocalLibraryShows(source: SourceType, readerOpen: Boolean, onShown: () -> Unit) {
    val latestOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(source, readerOpen) {
        if (source == SourceType.LOCAL && !readerOpen) latestOnShown()
    }
}
