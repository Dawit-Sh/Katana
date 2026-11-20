package com.example.unifiedreader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Marks of local copies of server books: each catalog book gains `remoteFileSizeKb`, its file's size on the
 * server (empty until the next library sync), and `local_server_links` keeps which local file is which
 * server book, checked by size and fingerprint. Additive only: every row keeps what it had.
 */
internal val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `readable_items` ADD COLUMN `remoteFileSizeKb` INTEGER DEFAULT NULL")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_server_links` (`localItemId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, `remoteBookId` TEXT, `fileSizeBytes` INTEGER NOT NULL, `partialMd5` TEXT NOT NULL, " +
                "`remoteFileSizeKb` INTEGER, `rejectedBookIds` TEXT NOT NULL, `checkedAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`localItemId`))",
        )
    }
}
