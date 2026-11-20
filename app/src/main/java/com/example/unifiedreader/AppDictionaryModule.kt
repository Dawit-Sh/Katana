package com.example.unifiedreader

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.network.buildHttpClient
import com.example.unifiedreader.feature.annotations.WordMeanings
import com.example.unifiedreader.feature.dictionary.Dictionary
import com.example.unifiedreader.feature.dictionary.DictionaryWordMeanings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** The offline English dictionary (downloaded in Settings › Reading); kept out of [AppModule]. */
@Module
@InstallIn(SingletonComponent::class)
object AppDictionaryModule {
    @Provides
    @Singleton
    fun provideDictionary(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ): Dictionary = Dictionary.create(
        directory = File(context.filesDir, "dictionary"),
        scope = applicationScope,
        // GitHub's release host, on the internet: wait longer than the LAN client does.
        httpClient = buildHttpClient(
            connectTimeoutMillis = DICTIONARY_CONNECT_TIMEOUT_MILLIS,
            socketTimeoutMillis = DICTIONARY_SOCKET_TIMEOUT_MILLIS,
        ),
    )

    @Provides
    @Singleton
    fun provideWordMeanings(dictionary: Dictionary): WordMeanings = DictionaryWordMeanings(dictionary)

    private const val DICTIONARY_CONNECT_TIMEOUT_MILLIS = 10_000L
    private const val DICTIONARY_SOCKET_TIMEOUT_MILLIS = 30_000L
}

/** Hands the singleton dictionary to the composition (the readers' Dictionary card, Settings › Reading). */
@HiltViewModel
class AppDictionaryViewModel @Inject constructor(
    val dictionary: Dictionary,
) : ViewModel()
