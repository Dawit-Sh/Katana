package com.example.unifiedreader

import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.BookmarkSyncStore
import com.example.unifiedreader.feature.annotations.HighlightSyncStore
import com.example.unifiedreader.feature.annotations.RoomBookmarkPlacementStore
import com.example.unifiedreader.feature.annotations.RoomBookmarkSyncStore
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.RoomHighlightPlacementStore
import com.example.unifiedreader.feature.annotations.RoomHighlightSyncStore
import com.example.unifiedreader.feature.annotations.RoomReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.withPlacement
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The reader's highlights (and their sync), bookmarks and saved words; kept out of [AppModule], which should not grow. */
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
    ).withPlacement(
        RoomHighlightPlacementStore(
            dao = database.readerHighlightPlacementDao(),
            bookmarks = RoomBookmarkPlacementStore(database.readerBookmarkDao()),
        ),
    )

    /** The highlights as their sync with a server reads and writes them. */
    @Provides
    @Singleton
    fun provideHighlightSyncStore(
        database: UnifiedReaderDatabase,
    ): HighlightSyncStore = RoomHighlightSyncStore(database = database)

    /** The bookmarks as their sync with a server reads and writes them. */
    @Provides
    @Singleton
    fun provideBookmarkSyncStore(
        database: UnifiedReaderDatabase,
    ): BookmarkSyncStore = RoomBookmarkSyncStore(
        dao = database.readerBookmarkSyncDao(),
        bookmarks = database.readerBookmarkDao(),
        runInTransaction = { block -> database.runInTransaction(Runnable(block)) },
    )

    /** When highlights sync with Grimmory; the app shell starts it and asks it. */
    @Provides
    fun provideHighlightSyncTriggers(
        coordinator: AppHighlightSyncCoordinator,
    ): HighlightSyncTriggers = coordinator

    /** The place the Bookmarks page asks a book to open at, which the reader takes. */
    @Provides
    @Singleton
    fun provideAnnotationJumps(): AnnotationJumps = AnnotationJumps()
}
