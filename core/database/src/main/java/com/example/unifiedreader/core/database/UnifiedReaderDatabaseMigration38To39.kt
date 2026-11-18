package com.example.unifiedreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Page bookmarks (`reader_bookmarks`, with what a sync needs later, like the highlights) and a
 * flashcard review state on each saved word (its level, when it is next due, when it was last
 * seen). The bookmarks start empty; the saved words keep their rows, all at level 0 and never
 * reviewed.
 */
internal val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reader_bookmarks` (
                `id` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `bookTitle` TEXT NOT NULL,
                `bookAuthor` TEXT,
                `href` TEXT NOT NULL,
                `locatorJson` TEXT NOT NULL,
                `chapterTitle` TEXT,
                `progression` REAL,
                `page` INTEGER,
                `pageCount` INTEGER,
                `text` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `remoteId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reader_bookmarks_itemId` ON `reader_bookmarks` (`itemId`)",
        )
        database.execSQL("ALTER TABLE `saved_words` ADD COLUMN `reviewLevel` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `saved_words` ADD COLUMN `reviewDueAtEpochMillis` INTEGER")
        database.execSQL("ALTER TABLE `saved_words` ADD COLUMN `reviewedAtEpochMillis` INTEGER")
    }
}
