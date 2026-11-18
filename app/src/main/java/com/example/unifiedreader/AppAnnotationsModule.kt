package com.example.unifiedreader

import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.RoomReaderAnnotationStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The reader's highlights and saved words; kept out of [AppModule], which should not grow. */
@Module
@InstallIn(SingletonComponent::class)
object AppAnnotationsModule {
    @Provides
    @Singleton
    fun provideReaderAnnotationStore(
        database: UnifiedReaderDatabase,
    ): ReaderAnnotationStore = RoomReaderAnnotationStore(dao = database.readerAnnotationDao())
}
