package com.example.unifiedreader.feature.dictionary

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** One row of the pack's senses joined to its gloss. */
data class PackSense(
    val partOfSpeech: PartOfSpeech,
    val definition: String,
    val example: String?,
)

/** The pack's tables, as the lookup reads them. */
interface DictionarySource : Closeable {
    /** [word]'s senses in the pack's order (by part of speech, the most common first). */
    fun senses(word: String): List<PackSense>

    /** The irregular base forms of [form] as [part] of speech (geese -> goose). */
    fun irregularBases(form: String, part: PartOfSpeech): List<String>
}

/**
 * The downloaded pack (tools/build-dictionary.py), opened read-only. Lookups use the index on
 * the word, so each is a few small reads. Call from a background thread.
 */
class SqliteDictionaryPack(file: File) : DictionarySource {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun senses(word: String): List<PackSense> = db.rawQuery(
        "SELECT s.pos, y.definition, y.example FROM senses s JOIN synsets y ON y.id = s.synset " +
            "WHERE s.word = ? ORDER BY s.pos, s.rank",
        arrayOf(word),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val part = PartOfSpeech.fromCode(cursor.getString(0)) ?: continue
                add(PackSense(part, cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2)))
            }
        }
    }

    override fun irregularBases(form: String, part: PartOfSpeech): List<String> = db.rawQuery(
        "SELECT lemma FROM exceptions WHERE form = ? AND pos = ?",
        arrayOf(form, part.code),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    override fun close() = db.close()
}

/**
 * Looks [text] up: the word itself first, then the base forms it may be an inflection of
 * ("running" -> run, "geese" -> goose, "studies" -> study), each part of speech with its first
 * [maxSenses] senses. Null when nothing matches.
 */
fun DictionarySource.lookUp(text: String, maxSenses: Int = DEFAULT_MAX_SENSES): DictionaryEntry? {
    val form = WordForms.normalize(text)
    if (form.isEmpty()) return null
    val meanings = mutableListOf<DictionaryMeaning>()
    val seen = mutableSetOf<Pair<String, PartOfSpeech>>()
    fun addMeanings(lemma: String, only: PartOfSpeech?) {
        // Nouns, verbs, adjectives, adverbs, as WordNet lists them (the pack's codes sort a, n, r, v).
        senses(lemma).groupBy(PackSense::partOfSpeech).entries.sortedBy { it.key.ordinal }.forEach { (part, senses) ->
            if ((only == null || part == only) && seen.add(lemma to part)) {
                meanings += DictionaryMeaning(
                    lemma = lemma,
                    partOfSpeech = part,
                    senses = senses.take(maxSenses).map { DictionarySense(it.definition, it.example) },
                )
            }
        }
    }
    addMeanings(form, only = null)
    PartOfSpeech.entries.forEach { part ->
        WordForms.bases(form, part, irregularBases(form, part)).forEach { base -> addMeanings(base, only = part) }
    }
    return if (meanings.isEmpty()) null else DictionaryEntry(word = form, meanings = meanings)
}

private const val DEFAULT_MAX_SENSES = 3
