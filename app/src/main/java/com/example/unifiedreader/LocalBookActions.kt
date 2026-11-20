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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage

/** Add a folder and Find books on this phone, for the library's "•••" (null where there's no library). */
class LocalBookActions(
    val onAddFolder: () -> Unit,
    val onFindOnPhone: () -> Unit,
    val findsOnPhone: Boolean,
    val onCheckForNewBooks: () -> Unit,
)

val LocalLocalBookActions = staticCompositionLocalOf<LocalBookActions?> { null }

/** The menu's phone item: Find books on this phone, or Check for new books once it is on. */
internal fun LocalBookActions?.findOnPhoneItem(): (() -> Unit)? =
    this?.let { actions -> if (actions.findsOnPhone) actions.onCheckForNewBooks else actions.onFindOnPhone }

internal fun LocalBookActions?.isFindingOnPhone(): Boolean = this?.findsOnPhone == true

/**
 * The folder picker, the permission Find books on this phone needs (All files access from Android 11, asked
 * in the system's own screen; storage before), and a look through the folders whenever the Local library
 * shows. What changed shows as a toast.
 */
@Composable
internal fun rememberLocalBookActions(localBooks: AppLocalBooksViewModel, viewModel: AppViewModel): LocalBookActions {
    val context = LocalContext.current
    val report: (LocalBookRefresh, Boolean) -> Unit = { result, asked ->
        result.toastText(asked)?.let { text -> viewModel.toasts.show(AppToastMessage(text = text, icon = AppToastIcon.Added)) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) localBooks.addFolder(tree) { result -> report(result, true) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) localBooks.findOnWholePhone { result -> report(result, true) }
    }
    // Back from the system's All files access screen: on, if it was allowed there.
    var waitingForAllFilesAccess by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForAllFilesAccess) {
                waitingForAllFilesAccess = false
                if (hasWholePhoneAccess(context)) localBooks.findOnWholePhone { result -> report(result, true) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state = viewModel.uiState
    LaunchedEffect(state.selectedSource, state.activeReader == null) {
        if (state.selectedSource == SourceType.LOCAL && state.activeReader == null) {
            localBooks.refreshIfDue { result -> report(result, false) }
        }
    }
    return remember(localBooks, localBooks.findsOnWholePhone) {
        LocalBookActions(
            onAddFolder = { folderPicker.launch(null) },
            onFindOnPhone = {
                when {
                    hasWholePhoneAccess(context) -> localBooks.findOnWholePhone { result -> report(result, true) }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        waitingForAllFilesAccess = true
                        val forKatana = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(forKatana) }
                            .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }
                    else -> storagePermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            },
            findsOnPhone = localBooks.findsOnWholePhone,
            onCheckForNewBooks = { localBooks.refresh { result -> report(result, true) } },
        )
    }
}
