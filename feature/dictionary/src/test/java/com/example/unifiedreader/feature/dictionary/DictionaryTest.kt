package com.example.unifiedreader.feature.dictionary

import com.example.unifiedreader.core.model.toLowerHex
import com.example.unifiedreader.core.network.IncompleteDownloadException
import com.example.unifiedreader.core.network.PartialDownloadFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DictionaryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val packBytes by lazy { TestPack.write(File(folder.root, "source/pack.sqlite")).readBytes() }
    private val info by lazy {
        DictionaryPackInfo(
            url = "https://example.test/pack.sqlite",
            sizeBytes = packBytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(packBytes).toLowerHex(),
            fileName = "katana-dictionary-en-1.sqlite",
        )
    }
    private val requested = mutableListOf<String>()

    private fun TestScope.dictionary(
        download: suspend (PartialDownloadFile, String) -> Unit = { target, url -> serve(target, url) },
    ) = Dictionary(
        directory = File(folder.root, "dictionary"),
        scope = this,
        download = download,
        pack = info,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun serve(target: PartialDownloadFile, url: String, bytes: ByteArray = packBytes) {
        requested += url
        target.dataFile.parentFile?.mkdirs()
        target.dataFile.appendBytes(bytes.copyOfRange(target.downloadedBytes.toInt(), bytes.size))
    }

    private fun installed(scope: TestScope): Dictionary = scope.dictionary().also { dictionary ->
        dictionary.install()
        scope.advanceUntilIdle()
    }

    @Test
    fun `with no dictionary the lookup says so, and the row offers the download with its size`() = runTest {
        val dictionary = dictionary()

        assertEquals(DictionaryInstall.NotInstalled, dictionary.state.value)
        assertEquals(DictionaryLookup.NotInstalled, dictionary.lookUp("geese"))
        val row = dictionaryRowCopy(dictionary.state.value, 17_588_224L)
        assertEquals("Download", row.button)
        assertTrue(row.supporting, row.supporting.endsWith("17 MB"))
        assertEquals(
            "Download the English dictionary (17 MB) in Settings to look words up on this device.",
            DictionaryCopy.notInstalled(17_588_224L),
        )
    }

    @Test
    fun `a download checked against its SHA-256 installs the pack, and Remove takes it off`() = runTest {
        val dictionary = installed(this)

        assertEquals(listOf(info.url), requested)
        assertEquals(DictionaryInstall.Installed(info.sizeBytes), dictionary.state.value)
        assertEquals("Remove", dictionaryRowCopy(dictionary.state.value, info.sizeBytes).button)
        assertTrue(dictionary.lookUp("run") is DictionaryLookup.Found)

        dictionary.remove()

        assertEquals(DictionaryInstall.NotInstalled, dictionary.state.value)
        assertEquals(DictionaryLookup.NotInstalled, dictionary.lookUp("run"))
        assertFalse(File(folder.root, "dictionary/${info.fileName}").exists())
    }

    @Test
    fun `a download with the wrong SHA-256 is thrown away`() = runTest {
        val damaged = packBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val dictionary = dictionary { target, url -> serve(target, url, damaged) }

        dictionary.install()
        advanceUntilIdle()

        assertEquals(DictionaryInstall.Failed("The download was damaged. Try again."), dictionary.state.value)
        assertEquals(DictionaryLookup.NotInstalled, dictionary.lookUp("run"))
        assertFalse(File(folder.root, "dictionary/${info.fileName}.part").exists())
    }

    @Test
    fun `a dropped connection keeps what arrived, and Download goes on from there`() = runTest {
        var tries = 0
        val dictionary = dictionary { target, url ->
            tries++
            if (tries == 1) {
                target.dataFile.parentFile?.mkdirs()
                target.dataFile.writeBytes(packBytes.copyOf(packBytes.size / 2))
                throw IncompleteDownloadException(packBytes.size.toLong(), packBytes.size / 2L)
            }
            assertEquals(packBytes.size / 2L, target.downloadedBytes)
            serve(target, url)
        }

        dictionary.install()
        advanceUntilIdle()
        val failed = dictionary.state.value as DictionaryInstall.Failed
        assertTrue(failed.message, failed.message.startsWith("The connection dropped"))
        assertEquals("Download", dictionaryRowCopy(failed, info.sizeBytes).button)

        dictionary.install()
        advanceUntilIdle()

        assertEquals(DictionaryInstall.Installed(info.sizeBytes), dictionary.state.value)
    }

    @Test
    fun `the download row shows its progress`() {
        val row = dictionaryRowCopy(DictionaryInstall.Downloading(downloadedBytes = 4_194_304L, totalBytes = 16_777_216L), 0L)

        assertEquals("Downloading · 4 MB of 16 MB", row.supporting)
        assertEquals(0.25f, row.progress!!, 0.001f)
        assertNull(row.button)
    }

    @Test
    fun `inflected words find their base forms`() = runTest {
        val dictionary = installed(this)

        suspend fun lemmas(word: String): List<Pair<String, PartOfSpeech>> =
            (dictionary.lookUp(word) as? DictionaryLookup.Found)?.entry?.meanings?.map { it.lemma to it.partOfSpeech }.orEmpty()

        assertEquals(listOf("goose" to PartOfSpeech.Noun), lemmas("geese"))
        // The word itself first (nouns before adjectives), then its base form.
        assertEquals(
            listOf("running" to PartOfSpeech.Noun, "running" to PartOfSpeech.Adjective, "run" to PartOfSpeech.Verb),
            lemmas("running"),
        )
        assertEquals(listOf("study" to PartOfSpeech.Noun, "study" to PartOfSpeech.Verb), lemmas("studies"))
        assertEquals(listOf("run" to PartOfSpeech.Verb), lemmas("ran"))
        assertEquals(listOf("big" to PartOfSpeech.Adjective), lemmas("bigger"))
        assertEquals(listOf("box" to PartOfSpeech.Noun), lemmas("boxes"))
        assertEquals(listOf("breathe" to PartOfSpeech.Verb), lemmas("Breathed,"))
        assertEquals(listOf("woman" to PartOfSpeech.Noun), lemmas("women’s"))
    }

    @Test
    fun `the graph's sense groups take a word's own and its base forms', and leave out unknown words`() = runTest {
        val dictionary = installed(this)

        val groups = dictionary.senseGroups(listOf("ran", "Running", "geese", "zzyzx"))

        assertEquals(setOf(11L, 12L, 13L), groups["ran"])
        // "running" is a word of its own (41, 42) and a form of run (11, 12, 13).
        assertEquals(setOf(41L, 42L, 11L, 12L, 13L), groups["Running"])
        assertEquals(setOf(21L), groups["geese"])
        assertFalse("zzyzx" in groups)
        // ran and running share run's groups: the graph joins them.
        assertTrue(groups.getValue("ran").intersect(groups.getValue("Running")).isNotEmpty())
        assertEquals(groups, DictionaryWordSynonyms(dictionary).senseGroups(listOf("ran", "Running", "geese", "zzyzx")))
    }

    @Test
    fun `a word is shown with its part of speech and first senses, the most common first`() = runTest {
        val dictionary = installed(this)

        val entry = (dictionary.lookUp("run") as DictionaryLookup.Found).entry

        assertEquals("run", entry.word)
        assertEquals(listOf(PartOfSpeech.Noun, PartOfSpeech.Verb), entry.meanings.map { it.partOfSpeech })
        assertEquals(
            listOf("move fast by using one's feet", "be in charge of"),
            entry.meanings[1].senses.map { it.definition },
        )
        assertEquals("a race run on foot", entry.firstDefinition)
    }

    @Test
    fun `a missing word says no definition was found`() = runTest {
        val dictionary = installed(this)

        assertEquals(DictionaryLookup.NotFound("zzyzx"), dictionary.lookUp(" Zzyzx. "))
        assertEquals("No definition found for ‘zzyzx’", DictionaryCopy.notFound("zzyzx"))
    }

    @Test
    fun `My words gets the first definition in English only, and a stored meaning comes first`() = runTest {
        val meanings = DictionaryWordMeanings(installed(this))

        assertEquals("web-footed long-necked migratory aquatic birds", meanings.meaning("geese", "en"))
        assertNull(meanings.meaning("geese", "fr"))
        assertNull(meanings.meaning("zzyzx", "en"))
        assertEquals("my own words", meanings.meaningOf("geese", stored = "my own words", language = "en"))
        assertEquals("a race run on foot", meanings.meaningOf("run", stored = " ", language = "en"))
    }

    @Test
    fun `a dictionary built into the app unpacks on the first lookup, with nothing to download`() = runTest {
        val dictionary = Dictionary(
            directory = File(folder.root, "dictionary"),
            scope = this,
            download = { _, _ -> error("a built-in dictionary never downloads") },
            pack = info,
            ioDispatcher = Dispatchers.Unconfined,
            bundled = { packBytes.inputStream() },
        )

        assertTrue(dictionary.isBuiltIn)
        assertTrue(dictionary.lookUp("geese") is DictionaryLookup.Found)
        assertTrue(dictionary.state.value is DictionaryInstall.Installed)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `a damaged built-in copy is not used, and the lookup says the dictionary isn't there`() = runTest {
        val dictionary = Dictionary(
            directory = File(folder.root, "dictionary"),
            scope = this,
            download = { _, _ -> error("a built-in dictionary never downloads") },
            pack = info,
            ioDispatcher = Dispatchers.Unconfined,
            bundled = { packBytes.copyOf(packBytes.size / 2).inputStream() },
        )

        assertEquals(DictionaryLookup.NotInstalled, dictionary.lookUp("geese"))
        assertFalse(File(folder.root, "dictionary/katana-dictionary-en-1.sqlite").exists())
    }
}
