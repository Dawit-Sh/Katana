package com.example.unifiedreader.feature.dictionary

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * A dozen words in the pack's schema (tools/build-dictionary.py): a few nouns, verbs and an
 * adjective, with WordNet's irregular forms for geese, ran and running.
 */
internal object TestPack {
    private val Synsets = listOf(
        Triple(11, "a race run on foot", "she broke the record for the half-mile run"),
        Triple(12, "move fast by using one's feet", "Don't run--you'll be out of breath"),
        Triple(13, "be in charge of", "Who runs the store?"),
        Triple(21, "web-footed long-necked migratory aquatic birds", null),
        Triple(31, "a detailed critical inspection", null),
        Triple(32, "learn by reading books", "He is studying geology"),
        Triple(41, "the act of running", null),
        Triple(42, "moving or issuing in a stream", "freely running water"),
        Triple(51, "large in size or number", "a big car"),
        Triple(61, "a written work or composition that has been published", null),
        Triple(71, "draw air into, and expel out of, the lungs", "I can breathe better now"),
        Triple(81, "a box with a lid", null),
        Triple(91, "an adult female person", null),
    )

    private val Senses = listOf(
        Sense("run", "n", 0, 11),
        Sense("run", "v", 0, 12),
        Sense("run", "v", 1, 13),
        Sense("goose", "n", 0, 21),
        Sense("study", "n", 0, 31),
        Sense("study", "v", 0, 32),
        Sense("running", "a", 0, 42),
        Sense("running", "n", 0, 41),
        Sense("big", "a", 0, 51),
        Sense("book", "n", 0, 61),
        Sense("breathe", "v", 0, 71),
        Sense("box", "n", 0, 81),
        Sense("woman", "n", 0, 91),
    )

    private val Exceptions = listOf(
        Triple("geese", "n", "goose"),
        Triple("ran", "v", "run"),
        Triple("running", "v", "run"),
        Triple("bigger", "a", "big"),
        Triple("women", "n", "woman"),
    )

    fun write(file: File): File {
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE synsets(id INTEGER PRIMARY KEY, definition TEXT NOT NULL, example TEXT)")
            db.execSQL("CREATE TABLE senses(word TEXT NOT NULL, pos TEXT NOT NULL, rank INTEGER NOT NULL, synset INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE exceptions(form TEXT NOT NULL, pos TEXT NOT NULL, lemma TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('format', '1')")
            Synsets.forEach { (id, definition, example) ->
                db.execSQL("INSERT INTO synsets VALUES (?, ?, ?)", arrayOf<Any?>(id, definition, example))
            }
            Senses.forEach { sense ->
                db.execSQL("INSERT INTO senses VALUES (?, ?, ?, ?)", arrayOf<Any?>(sense.word, sense.pos, sense.rank, sense.synset))
            }
            Exceptions.forEach { (form, pos, lemma) ->
                db.execSQL("INSERT INTO exceptions VALUES (?, ?, ?)", arrayOf<Any?>(form, pos, lemma))
            }
            db.execSQL("CREATE INDEX senses_word ON senses(word)")
            db.execSQL("CREATE INDEX exceptions_form ON exceptions(form)")
        }
        return file
    }

    private data class Sense(val word: String, val pos: String, val rank: Int, val synset: Int)
}
