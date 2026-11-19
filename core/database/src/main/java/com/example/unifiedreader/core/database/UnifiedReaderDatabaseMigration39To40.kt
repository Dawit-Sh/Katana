package com.example.unifiedreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Bookmark sync and naming: each bookmark gains `remoteCfi`, its place the server's way (an EPUB
 * CFI, empty until the reader works it out), and the reader's own `title` and `note`, empty. Every
 * bookmark and highlight keeps its row as it was.
 */
internal val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `reader_bookmarks` ADD COLUMN `remoteCfi` TEXT")
        database.execSQL("ALTER TABLE `reader_bookmarks` ADD COLUMN `title` TEXT")
        database.execSQL("ALTER TABLE `reader_bookmarks` ADD COLUMN `note` TEXT")
    }
}
