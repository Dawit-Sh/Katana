package com.example.unifiedreader

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.unifiedreader.core.common.AppDispatchers
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.feature.reader.preferences.DataStoreReaderBookLookPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.DataStoreReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.DataStoreReaderPageColorPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.DataStoreReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.DataStoreReaderPagesPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.DataStoreReadingGoalStore
import com.example.unifiedreader.feature.reader.preferences.ReaderDisplayPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus

/**
 * The readers' shared page settings (page number, page colour, light and keys, PDF pages, the daily
 * reading goal); kept out of
 * [AppModule], which should not grow.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReaderDisplayPreferencesModule {
    @Provides
    @Singleton
    fun provideReaderDisplayPreferences(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        appDispatchers: AppDispatchers,
    ): ReaderDisplayPreferences = ReaderDisplayPreferences(
        pageNumbers = DataStoreReaderPageNumberPreferencesStore(context, scope = applicationScope + appDispatchers.io),
        pageColor = DataStoreReaderPageColorPreferencesStore(context, scope = applicationScope + appDispatchers.io),
        bookLook = DataStoreReaderBookLookPreferencesStore(context, scope = applicationScope + appDispatchers.io),
        comfort = DataStoreReaderComfortPreferencesStore(context, scope = applicationScope + appDispatchers.io),
        pages = DataStoreReaderPagesPreferencesStore(context, scope = applicationScope + appDispatchers.io),
        readingGoal = DataStoreReadingGoalStore(context, scope = applicationScope + appDispatchers.io),
    )
}

/** Hands the readers' shared settings to the composition (Settings › Reading: the screen and the volume keys). */
@HiltViewModel
class AppReaderDisplayViewModel @Inject constructor(
    val preferences: ReaderDisplayPreferences,
) : ViewModel()
