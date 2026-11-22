package com.example.unifiedreader.feature.annotations

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordGraphTest {
    private val carol = AnnotatedBook(itemId = "carol", title = "A Christmas Carol", author = "Charles Dickens")
    private val moby = AnnotatedBook(itemId = "moby", title = "Moby-Dick", author = "Herman Melville")
    private val pride = AnnotatedBook(itemId = "pride", title = "Pride and Prejudice", author = "Jane Austen")

    private val covetous = word("w1", carol, "covetous", at = 1)
    private val grasping = word("w2", carol, "grasping", at = 2)
    private val solitary = word("w3", carol, "solitary", at = 3)
    private val avaricious = word("w4", moby, "avaricious", at = 4)
    private val leviathan = word("w5", moby, "leviathan", at = 5)
    private val haughty = word("w6", pride, "haughty", at = 6)
    private val dreary = word("w7", pride, "dreary", at = 7)
    private val drearyAgain = word("w8", moby, "Dreary", at = 8)
    private val all = listOf(covetous, grasping, solitary, avaricious, leviathan, haughty, dreary, drearyAgain)

    // As the dictionary would answer: covetous, grasping and avaricious share "greedy" (10); haughty stands alone.
    private val groups = mapOf(
        "covetous" to setOf(10L, 11L),
        "grasping" to setOf(10L),
        "avaricious" to setOf(10L, 12L),
        "solitary" to setOf(20L),
        "haughty" to setOf(30L),
        "leviathan" to setOf(40L),
        "dreary" to setOf(50L),
        "Dreary" to setOf(50L),
    )

    @Test
    fun `a word is new until reviewed, learned from the week's step, learning in between`() {
        assertEquals(WordState.New, wordState(covetous))
        assertEquals(WordState.Learning, wordState(covetous.copy(reviewLevel = 1, reviewedAtEpochMillis = 5)))
        assertEquals(WordState.Learning, wordState(covetous.copy(reviewLevel = 2, reviewedAtEpochMillis = 5)))
        assertEquals(WordState.Learned, wordState(covetous.copy(reviewLevel = 3, reviewedAtEpochMillis = 5)))
        assertEquals(WordState.Learned, wordState(covetous.copy(reviewLevel = 5, reviewedAtEpochMillis = 5)))
        // Again sends it back to level 0, but it has been reviewed: learning, not new.
        assertEquals(WordState.Learning, wordState(covetous.copy(reviewLevel = 0, reviewedAtEpochMillis = 5)))
    }

    @Test
    fun `the state filter keeps only its own words`() {
        assertTrue(WordStateFilter.All.matches(WordState.New))
        assertTrue(WordStateFilter.Learned.matches(WordState.Learned))
        assertFalse(WordStateFilter.Learned.matches(WordState.Learning))
        assertTrue(WordStateFilter.New.matches(WordState.New))
        assertFalse(WordStateFilter.Learning.matches(WordState.New))
    }

    @Test
    fun `words sharing a sense group link, across books, each pair once`() {
        val links = synonymLinks(all, groups)
        assertTrue(WordLink("w1", "w2") in links)
        assertTrue(WordLink("w1", "w4") in links)
        assertTrue(WordLink("w2", "w4") in links)
        assertFalse(links.any { it.a == "w6" || it.b == "w6" })
        assertEquals(links.size, links.toSet().size)
        assertTrue(links.all { it.a < it.b })
    }

    @Test
    fun `the same word saved twice links, whatever its case`() {
        val links = synonymLinks(all, emptyMap())
        assertEquals(listOf(WordLink("w7", "w8")), links)
    }

    @Test
    fun `no dictionary, no links but the same word`() {
        assertTrue(synonymLinks(listOf(covetous, grasping), emptyMap()).isEmpty())
    }

    @Test
    fun `every book's graph has every word and book, placed the same each time`() {
        val first = buildWordGraph(all, groups, bookId = null)
        val second = buildWordGraph(all.shuffled(java.util.Random(4)), groups, bookId = null)
        assertEquals(all.map(SavedWord::id).toSet(), first.positions.keys)
        assertEquals(setOf("carol", "moby", "pride"), first.bookPositions.keys)
        assertEquals(first.positions, second.positions)
        assertEquals(first.bookPositions, second.bookPositions)
        assertTrue(first.ghosts.isEmpty())
    }

    @Test
    fun `the layout settles, words near their own book, nothing piled up or flung away`() {
        val graph = buildWordGraph(all, groups, bookId = null)
        all.forEach { word ->
            val p = graph.positions.getValue(word.id)
            val own = graph.bookPositions.getValue(word.book.itemId)
            val toOwn = hypot(p.x - own.x, p.y - own.y)
            assertTrue("${word.word} is ${toOwn} from its book", toOwn in 40f..400f)
            assertTrue(p.x.isFinite() && p.y.isFinite())
        }
        val points = graph.positions.values.toList()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                assertTrue(hypot(points[i].x - points[j].x, points[i].y - points[j].y) > 8f)
            }
        }
    }

    @Test
    fun `one book shows its words around it and other books' synonyms as ghosts`() {
        val graph = buildWordGraph(all, groups, bookId = "carol")
        assertEquals(listOf(solitary, grasping, covetous).map(SavedWord::id), graph.words.map(SavedWord::id))
        assertEquals(listOf("w4"), graph.ghosts.map(SavedWord::id))
        assertEquals(setOf("carol"), graph.bookPositions.keys)
        assertEquals(GraphPoint(0f, 0f), graph.bookPositions.getValue("carol"))
        assertTrue(graph.links.all { it.a in graph.wordsById && it.b in graph.wordsById })
        assertEquals(setOf("w2", "w4"), graph.synonymsOf.getValue("w1").toSet())
    }

    @Test
    fun `a big library still lays out, every point finite`() {
        val books = (0 until 12).map { AnnotatedBook("b$it", "Book $it", null) }
        val many = (0 until 300).map { i -> word("x$i", books[i % books.size], "word$i", at = i.toLong()) }
        val bigGroups = many.associate { it.word to setOf((it.word.removePrefix("word").toLong() % 40)) }
        val graph = buildWordGraph(many, bigGroups, bookId = null)
        assertEquals(300, graph.positions.size)
        assertTrue(graph.positions.values.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(graph.bounds.width > 0f && graph.bounds.height > 0f)
    }

    @Test
    fun `play brings the words in as they were saved, the oldest first`() {
        val graph = buildWordGraph(all, groups, bookId = null)
        assertEquals(all.sortedBy(SavedWord::createdAtEpochMillis).map(SavedWord::id), graph.playOrder.map(SavedWord::id))
        assertEquals(listOf("w1", "w2"), wordPlayOrder(listOf(grasping, covetous)).map(SavedWord::id))
    }

    @Test
    fun `a book's bounds hold its words and cover`() {
        val graph = buildWordGraph(all, groups, bookId = null)
        val box = graph.bookBounds("moby")
        listOf(avaricious, leviathan, drearyAgain).forEach { w ->
            val p = graph.positions.getValue(w.id)
            assertTrue(p.x in box.left..box.right && p.y in box.top..box.bottom)
        }
    }

    @Test
    fun `the play line and reviews read plainly`() {
        assertEquals("Play the links", WordGraphCopy.playLine(null, 34, null))
        assertTrue(WordGraphCopy.playLine(3, 34, null) == "3 of 34")
        assertEquals("Not reviewed yet", WordGraphCopy.reviews(covetous, now = 10))
        val day = 24L * 60 * 60 * 1000
        val reviewed = covetous.copy(reviewedAtEpochMillis = 0, reviewDueAtEpochMillis = 7 * day, reviewLevel = 3)
        assertTrue(WordGraphCopy.reviews(reviewed, now = 0).endsWith("next in 7 days"))
        assertTrue(WordGraphCopy.reviews(reviewed, now = 8 * day).endsWith("due now"))
    }

    private fun word(id: String, book: AnnotatedBook, text: String, at: Long) = SavedWord(
        id = id,
        book = book,
        word = text,
        sentence = null,
        place = null,
        note = null,
        createdAtEpochMillis = at,
    )
}
