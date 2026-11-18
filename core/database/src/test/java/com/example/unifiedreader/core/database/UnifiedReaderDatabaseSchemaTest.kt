package com.example.unifiedreader.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migrations checked against the schemas Room exports to `core/database/schemas/`, so a migration that
 * leaves a column, index or default different from what the entities declare fails here rather than on a
 * device. The schemas start at 36; every later version must add its file.
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedReaderDatabaseSchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        UnifiedReaderDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration 36 to 37 gives the exported 37 schema and keeps server lists`() {
        val name = "schema-36-37-${System.nanoTime()}"
        helper.createDatabase(name, 36).use { database ->
            database.execSQL(
                """
                INSERT INTO server_lists (id, sourceType, kind, remoteId, name, isPublic, isOwned, remoteItemCount,
                    orderMode, hideFinished, syncedAtEpochMillis)
                VALUES ('kavita:1', 'KAVITA', 'READING_LIST', '1', 'Crisis', 1, 0, 12, 'SERVER', 0, 5)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(name, 37, true, MIGRATION_36_37).use { database ->
            database.query("SELECT name, startYear, endYear FROM server_lists WHERE id = 'kavita:1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Crisis", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    fun `migration 37 to 38 gives the exported 38 schema with empty highlight and word tables`() {
        val name = "schema-37-38-${System.nanoTime()}"
        helper.createDatabase(name, 37).use { database ->
            database.execSQL(
                "INSERT INTO list_settings (`key`, `value`) VALUES ('lists.sort', 'NAME')",
            )
        }

        helper.runMigrationsAndValidate(name, 38, true, MIGRATION_37_38).use { database ->
            database.query("SELECT `value` FROM list_settings WHERE `key` = 'lists.sort'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("NAME", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM reader_highlights").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM saved_words").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `migration 38 to 39 gives the exported 39 schema, an empty bookmark table and unreviewed words`() {
        val name = "schema-38-39-${System.nanoTime()}"
        helper.createDatabase(name, 38).use { database ->
            database.execSQL(
                """
                INSERT INTO saved_words (id, itemId, bookTitle, bookAuthor, word, sentence, chapterTitle,
                    locatorJson, progression, note, createdAtEpochMillis)
                VALUES ('w1', 'grimmory:item:7', 'The Hole', 'Hiroko Oyamada', 'solar', 'solar panels on the roof',
                    NULL, NULL, 0.1, NULL, 5)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(name, 39, true, MIGRATION_38_39).use { database ->
            database.query("SELECT word, reviewLevel, reviewDueAtEpochMillis, reviewedAtEpochMillis FROM saved_words").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("solar", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
            database.query("SELECT COUNT(*) FROM reader_bookmarks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `room opens a version 36 database at the current version with every migration`() {
        val name = "schema-36-current-${System.nanoTime()}"
        helper.createDatabase(name, 36).close()

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UnifiedReaderDatabase::class.java,
            name,
        )
            .addMigrations(*allMigrations)
            .allowMainThreadQueries()
            .build()
        // Opening runs the migrations and checks the result against the entities.
        database.openHelper.writableDatabase
        helper.closeWhenFinished(database)
    }

    @Test
    fun `every version from 36 on has an exported schema`() {
        val schemas = File(System.getProperty("user.dir"))
            .resolve("schemas/com.example.unifiedreader.core.database.UnifiedReaderDatabase")
        val versions = schemas.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> file.nameWithoutExtension.toIntOrNull() }
            ?.sorted()
            .orEmpty()
        val latest = allMigrations.maxOf { migration -> migration.endVersion }
        assertEquals((36..latest).toList(), versions)
    }
}
