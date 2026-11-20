package com.example.unifiedreader

import android.content.Context
import android.net.Uri
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

    val findsOnWholePhone: Boolean get() = places.findsOnWholePhone

    fun addFolder(tree: Uri, onResult: (LocalBookRefresh) -> Unit) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        places.addFolder(tree)
        refresh(onResult)
    }

    /** Turns the whole-phone search on, once Katana may read the phone ([hasWholePhoneAccess]). */
    fun findOnWholePhone(onResult: (LocalBookRefresh) -> Unit) {
        places.findsOnWholePhone = true
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
    added > 0 && removed > 0 -> "${books(added)} added, ${books(removed)} gone from the phone"
    added > 0 -> "${books(added)} added"
    removed > 0 -> "${books(removed)} gone from the phone"
    asked -> "No new books found"
    else -> null
}

private fun books(count: Int) = if (count == 1) "1 book" else "$count books"
