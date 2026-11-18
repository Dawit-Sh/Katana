package com.example.unifiedreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The reader's own marks on a book's text: highlights (`reader_highlights`, with what a sync needs
 * later) and the words saved to look up (`saved_words`). Both start empty; nothing else changes.
 */
internal val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reader_highlights` (
                `id` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `bookTitle` TEXT NOT NULL,
                `bookAuthor` TEXT,
                `href` TEXT NOT NULL,
                `locatorJson` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `chapterTitle` TEXT,
                `progression` REAL,
                `style` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                `note` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `remoteId` TEXT,
                `remoteCfi` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reader_highlights_itemId` ON `reader_highlights` (`itemId`)",
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_words` (
                `id` TEXT NOT NULL,
                `itemId` TEXT NOT NULL,
                `bookTitle` TEXT NOT NULL,
                `bookAuthor` TEXT,
                `word` TEXT NOT NULL,
                `sentence` TEXT,
                `chapterTitle` TEXT,
                `locatorJson` TEXT,
                `progression` REAL,
                `note` TEXT,
                `createdAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_words_itemId` ON `saved_words` (`itemId`)")
    }
}
