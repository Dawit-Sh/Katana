package com.example.unifiedreader

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
object AppLocalBooksModule {
    @Provides
    @Singleton
    fun provideLocalBookPlaces(@ApplicationContext context: Context): LocalBookPlaces = LocalBookPlaces(context)

    @Provides
    @Singleton
    fun provideLocalBookRefresher(
        @ApplicationContext context: Context,
        places: LocalBookPlaces,
        library: LocalDocumentDataSource,
    ): LocalBookRefresher = LocalBookRefresher(places, LocalBookFinder(context), library)
}

/**
 * Books on this device found in folders or on the whole phone (Add a folder, Find books on this phone in the
 * library's "•••"), brought in again when the Local library shows. [onResult] says what changed.
 */
@HiltViewModel
class AppLocalBooksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val places: LocalBookPlaces,
    private val refresher: LocalBookRefresher,
) : ViewModel() {
    private var lastRefreshAt = 0L

    /** The places as Book folders lists them; the menu and the list follow it. */
    var shownPlaces by mutableStateOf(currentPlaces())
        private set

    val findsOnWholePhone: Boolean get() = shownPlaces.wholePhone

    private fun currentPlaces() = LocalBookPlacesShown(
        folders = places.folders.map { tree -> tree to folderLabel(tree) },
        wholePhone = places.findsOnWholePhone,
    )

    /** Removes a folder from Book folders and its books from the library; [onResult] gets how many left. */
    fun removeFolder(tree: Uri, onResult: (Int) -> Unit) = forget(LocalBookPlace.Folder(tree), onResult) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Stops looking through the whole phone; the books it found leave the library. */
    fun stopFindingOnPhone(onResult: (Int) -> Unit) = forget(LocalBookPlace.WholePhone, onResult) {}

    private fun forget(place: LocalBookPlace, onResult: (Int) -> Unit, after: () -> Unit) {
        viewModelScope.launch {
            val removed = refresher.forget(place)
            after()
            shownPlaces = currentPlaces()
            onResult(removed)
        }
    }

    fun addFolder(tree: Uri, onResult: (LocalBookRefresh) -> Unit) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        places.addFolder(tree)
        shownPlaces = currentPlaces()
        refresh(onResult)
    }

    /** Turns the whole-phone search on, once Katana may read the phone ([hasWholePhoneAccess]). */
    fun findOnWholePhone(onResult: (LocalBookRefresh) -> Unit) {
        places.findsOnWholePhone = true
        shownPlaces = currentPlaces()
        refresh(onResult)
    }

    /** When the Local library shows: at most every few minutes, and only with folders or the phone search on. */
    fun refreshIfDue(onResult: (LocalBookRefresh) -> Unit) {
        if (!places.hasPlaces || System.currentTimeMillis() - lastRefreshAt < REFRESH_EVERY_MILLIS) return
        refresh(onResult)
    }

    fun refresh(onResult: (LocalBookRefresh) -> Unit) {
        lastRefreshAt = System.currentTimeMillis()
        viewModelScope.launch { onResult(refresher.refresh()) }
    }

    private companion object {
        const val REFRESH_EVERY_MILLIS = 5 * 60_000L
    }
}

/** The toast after a look through the folders and the phone; null when nothing changed and nobody asked. */
internal fun LocalBookRefresh.toastText(asked: Boolean): String? = when {
    added > 0 && removed > 0 -> "${books(added)} added, ${books(removed)} gone from this device"
    added > 0 -> "${books(added)} added"
    removed > 0 -> "${books(removed)} gone from this device"
    asked -> "No new books found"
    else -> null
}

private fun books(count: Int) = if (count == 1) "1 book" else "$count books"

/** The places Book folders lists: each picked folder with its label, and whether the whole phone is looked through. */
data class LocalBookPlacesShown(
    val folders: List<Pair<Uri, String>>,
    val wholePhone: Boolean,
) {
    val any: Boolean get() = wholePhone || folders.isNotEmpty()
}

internal fun removedToastText(removed: Int): String = if (removed == 1) {
    "Removed, with its 1 book. The file stays on this device"
} else {
    "Removed, with its $removed books. The files stay on this device"
}
