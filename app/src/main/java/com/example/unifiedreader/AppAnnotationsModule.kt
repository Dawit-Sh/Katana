package com.example.unifiedreader

import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.RoomReaderAnnotationStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The reader's highlights, bookmarks and saved words; kept out of [AppModule], which should not grow. */
@Module
@InstallIn(SingletonComponent::class)
object AppAnnotationsModule {
    @Provides
    @Singleton
    fun provideReaderAnnotationStore(
        database: UnifiedReaderDatabase,
    ): ReaderAnnotationStore = RoomReaderAnnotationStore(
        dao = database.readerAnnotationDao(),
        bookmarkDao = database.readerBookmarkDao(),
        studyDao = database.savedWordStudyDao(),
    )

    /** The place the Bookmarks page asks a book to open at, which the reader takes. */
    @Provides
    @Singleton
    fun provideAnnotationJumps(): AnnotationJumps = AnnotationJumps()
}
