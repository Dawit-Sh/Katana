package com.example.unifiedreader.feature.annotations

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How far a saved word is learned, from its flashcard reviews (My words' graph colours its circle by it). */
enum class WordState { Learned, Learning, New }

/**
 * Never reviewed: new. At the week's step or past it (I know it three times in a row): learned. Reviewed, but
 * not there yet (or sent back with Again): learning.
 */
fun wordState(word: SavedWord): WordState = when {
    word.reviewedAtEpochMillis == null && word.reviewLevel <= 0 -> WordState.New
    word.reviewLevel >= LEARNED_REVIEW_LEVEL -> WordState.Learned
    else -> WordState.Learning
}

/** The review level from which a word counts as learned: its next card is a week or more away. */
const val LEARNED_REVIEW_LEVEL = 3

/** The graph's state filter: every word, or only the learned, learning or new ones (the rest fade). */
enum class WordStateFilter {
    All,
    Learned,
    Learning,
    New,
    ;

    fun matches(state: WordState): Boolean = when (this) {
        All -> true
        Learned -> state == WordState.Learned
        Learning -> state == WordState.Learning
        New -> state == WordState.New
    }
}

/**
 * Where saved words' meanings come from for the graph: each word's sense groups in the dictionary (WordNet's
 * synsets), so two words that share one mean the same in some sense.
 */
interface WordSynonyms {
    /** For each of [words] (as saved), the ids of its sense groups; a word the dictionary doesn't know is left out. */
    suspend fun senseGroups(words: Collection<String>): Map<String, Set<Long>>

    /** Knows no meanings: the graph shows no synonym lines. */
    object None : WordSynonyms {
        override suspend fun senseGroups(words: Collection<String>): Map<String, Set<Long>> = emptyMap()
    }
}

/** Two saved words (by id) with a meaning in common, drawn as a zigzag. */
data class WordLink(val a: String, val b: String)

/**
 * The saved words that mean the same: two words sharing a sense group in [groups] (keyed by the word as saved),
 * or the same word saved twice (from two books, or two places). Each pair once, in a steady order.
 */
fun synonymLinks(words: List<SavedWord>, groups: Map<String, Set<Long>>): List<WordLink> {
    val byGroup = HashMap<Long, MutableList<SavedWord>>()
    val byText = HashMap<String, MutableList<SavedWord>>()
    words.forEach { word ->
        groups[word.word].orEmpty().forEach { group -> byGroup.getOrPut(group) { mutableListOf() } += word }
        byText.getOrPut(word.word.trim().lowercase(Locale.ROOT)) { mutableListOf() } += word
    }
    val seen = HashSet<Pair<String, String>>()
    val links = mutableListOf<WordLink>()
    fun linkAll(same: List<SavedWord>) {
        for (i in same.indices) {
            for (j in i + 1 until same.size) {
                val (a, b) = listOf(same[i].id, same[j].id).sorted()
                if (a != b && seen.add(a to b)) links += WordLink(a, b)
            }
        }
    }
    byText.values.forEach(::linkAll)
    byGroup.values.forEach(::linkAll)
    return links.sortedWith(compareBy(WordLink::a, WordLink::b))
}

/** A point on the graph, in the layout's own units (about a pixel at scale 1). */
data class GraphPoint(val x: Float, val y: Float)

/** The edges of what a layout holds, for fitting it to the screen. */
data class GraphBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        fun of(points: Collection<GraphPoint>, margin: Float): GraphBounds {
            if (points.isEmpty()) return GraphBounds(-margin, -margin, margin, margin)
            return GraphBounds(
                left = points.minOf(GraphPoint::x) - margin,
                top = points.minOf(GraphPoint::y) - margin,
                right = points.maxOf(GraphPoint::x) + margin,
                bottom = points.maxOf(GraphPoint::y) + margin,
            )
        }
    }
}

/**
 * Everything the graph draws, worked out off the main thread: the words shown, the books they hang from, the
 * synonym links, where each sits, and in book mode the other books' words that mean the same ([ghosts], drawn
 * faint around the edge).
 */
class WordGraphModel(
    /** The words shown, the newest first, as My words lists them. */
    val words: List<SavedWord>,
    /** Other books' words linked to [words] in book mode; empty for every book. */
    val ghosts: List<SavedWord>,
    /** The books that have nodes: the one book in book mode, every book of [words] otherwise. */
    val books: List<AnnotatedBook>,
    val links: List<WordLink>,
    /** Where the layout put each book and word. */
    placement: GraphPlacement,
    /** The one book in book mode; null for every book. */
    val bookId: String?,
) {
    val positions: Map<String, GraphPoint> = placement.words
    val bookPositions: Map<String, GraphPoint> = placement.books

    val states: Map<String, WordState> = (words + ghosts).associate { it.id to wordState(it) }

    /** Each word's linked words, by id. */
    val synonymsOf: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        links.forEach { link ->
            getOrPut(link.a) { mutableListOf() } += link.b
            getOrPut(link.b) { mutableListOf() } += link.a
        }
    }

    val wordsById: Map<String, SavedWord> = (words + ghosts).associateBy(SavedWord::id)

    /** The words in the order they were saved, for Play the links. */
    val playOrder: List<SavedWord> = wordPlayOrder(words)

    val bounds: GraphBounds = GraphBounds.of(positions.values + bookPositions.values, margin = BOUNDS_MARGIN)

    fun degree(id: String): Int = synonymsOf[id]?.size ?: 0

    /** The area of [bookId]'s words and cover, to zoom to the book. */
    fun bookBounds(bookId: String): GraphBounds {
        val points = words.filter { it.book.itemId == bookId }.mapNotNull { positions[it.id] } +
            listOfNotNull(bookPositions[bookId])
        return GraphBounds.of(points, margin = BOUNDS_MARGIN)
    }

    val isEmpty: Boolean get() = words.isEmpty()

    companion object {
        private const val BOUNDS_MARGIN = 70f
    }
}

/** The words in the order they were saved (the oldest first), as Play the links brings them in. */
fun wordPlayOrder(words: List<SavedWord>): List<SavedWord> =
    words.sortedWith(compareBy(SavedWord::createdAtEpochMillis, SavedWord::id))

/**
 * Builds the graph for [all] saved words: every book's words for a null [bookId], else that book's with the other
 * books' words that mean the same as ghosts. [groups] are the words' sense groups ([WordSynonyms]).
 */
fun buildWordGraph(all: List<SavedWord>, groups: Map<String, Set<Long>>, bookId: String?): WordGraphModel {
    val newestFirst = all.sortedByDescending(SavedWord::createdAtEpochMillis)
    val allLinks = synonymLinks(newestFirst, groups)
    return if (bookId == null) {
        val books = newestFirst.reversed().map(SavedWord::book).distinctBy(AnnotatedBook::itemId)
        val layout = WordGraphLayouts.everyBook(books, wordPlayOrder(newestFirst), allLinks)
        WordGraphModel(newestFirst, emptyList(), books, allLinks, layout, bookId = null)
    } else {
        val shown = newestFirst.filter { it.book.itemId == bookId }
        val shownIds = shown.mapTo(HashSet(), SavedWord::id)
        val links = allLinks.filter { it.a in shownIds || it.b in shownIds }
        val ghostIds = links.flatMap { listOf(it.a, it.b) }.filterNot { it in shownIds }.toSet()
        val ghosts = newestFirst.filter { it.id in ghostIds }
        val book = shown.firstOrNull()?.book
        val layout = WordGraphLayouts.oneBook(bookId, wordPlayOrder(shown), ghosts, links)
        WordGraphModel(shown, ghosts, listOfNotNull(book), links, layout, bookId = bookId)
    }
}

/** Where a layout put the books and the words. */
class GraphPlacement(val books: Map<String, GraphPoint>, val words: Map<String, GraphPoint>)

/**
 * The graph's layout: a small force simulation that settles (words pushed apart, pulled to their book, synonyms
 * pulled together), started from steady positions so the same words always land in the same places. Plain
 * arrays, no allocation per step: it runs off the main thread, quickly enough on the Fire 7 for a few hundred words.
 */
object WordGraphLayouts {
    private const val TAU = (2 * PI).toFloat()
    private const val GOLDEN_ANGLE = 2.3999632f
    private const val MIN_RING = 110f
    private const val RING_SPACING = 30f
    private const val BOOK_SPACING = 2.6f
    private const val GHOST_DISTANCE = 1.75f

    private const val WORD_TO_BOOK = 0.06f
    private const val SYNONYM_SPRING = 0.008f
    private const val BOOK_SYNONYM_SPRING = 0.01f
    private const val GHOST_SPRING = 0.06f
    private const val SYNONYM_REST = 190f
    private const val BOOK_SYNONYM_REST = 150f
    private const val GHOST_REST = 105f

    /** The ring a book's words sit on: wider for more words, so their names have room. */
    fun ringRadius(count: Int): Float = max(MIN_RING, count * RING_SPACING / TAU)

    /** Every book, each with its words around it; books spread out on a spiral, the first saved-from in the middle. */
    fun everyBook(books: List<AnnotatedBook>, words: List<SavedWord>, links: List<WordLink>): GraphPlacement {
        val byBook = words.groupBy { it.book.itemId }
        val widestRing = books.maxOfOrNull { ringRadius(byBook[it.itemId]?.size ?: 0) } ?: MIN_RING
        val anchors = books.mapIndexed { i, book ->
            val r = widestRing * BOOK_SPACING * sqrt(i.toFloat())
            val a = i * GOLDEN_ANGLE
            book.itemId to GraphPoint(r * cos(a), r * sin(a))
        }.toMap()
        val sim = Simulation()
        books.forEach { book -> sim.addNode(book.itemId, anchors.getValue(book.itemId), mass = BOOK_MASS, anchor = true) }
        val wordIds = words.mapTo(HashSet(), SavedWord::id)
        books.forEach { book ->
            val inBook = byBook[book.itemId].orEmpty()
            val center = anchors.getValue(book.itemId)
            val ring = ringRadius(inBook.size)
            inBook.forEachIndexed { k, word ->
                val a = -PI.toFloat() / 2 + k * TAU / max(1, inBook.size)
                sim.addNode(word.id, GraphPoint(center.x + ring * cos(a), center.y + ring * sin(a)))
                sim.addLink(word.id, book.itemId, ring, WORD_TO_BOOK)
            }
        }
        links.filter { it.a in wordIds && it.b in wordIds }.forEach { sim.addLink(it.a, it.b, SYNONYM_REST, SYNONYM_SPRING) }
        sim.run()
        return GraphPlacement(books.associate { it.itemId to sim.position(it.itemId) }, words.associate { it.id to sim.position(it.id) })
    }

    /** One book in the middle, its words around it, and other books' synonyms of them a little further out. */
    fun oneBook(bookId: String, words: List<SavedWord>, ghosts: List<SavedWord>, links: List<WordLink>): GraphPlacement {
        val sim = Simulation()
        sim.addNode(bookId, GraphPoint(0f, 0f), mass = BOOK_MASS, fixed = true)
        val ring = ringRadius(words.size) + RING_SPACING
        words.forEachIndexed { k, word ->
            val a = -PI.toFloat() / 2 + k * TAU / max(1, words.size)
            sim.addNode(word.id, GraphPoint(ring * cos(a), ring * sin(a)))
            sim.addLink(word.id, bookId, ring, WORD_TO_BOOK)
        }
        val shown = words.mapTo(HashSet(), SavedWord::id)
        placeGhosts(sim, ghosts, links, shown, ring)
        val ghostIds = ghosts.mapTo(HashSet(), SavedWord::id)
        links.forEach { link ->
            when {
                link.a in shown && link.b in shown -> sim.addLink(link.a, link.b, BOOK_SYNONYM_REST, BOOK_SYNONYM_SPRING)
                link.a in ghostIds || link.b in ghostIds -> if (sim.has(link.a) && sim.has(link.b)) {
                    sim.addLink(link.a, link.b, GHOST_REST, GHOST_SPRING)
                }
            }
        }
        sim.run()
        return GraphPlacement(
            books = mapOf(bookId to sim.position(bookId)),
            words = (words + ghosts).associate { it.id to sim.position(it.id) },
        )
    }

    /** Other books' synonyms start further out, each near the word it means the same as. */
    private fun placeGhosts(sim: Simulation, ghosts: List<SavedWord>, links: List<WordLink>, shown: Set<String>, ring: Float) {
        ghosts.forEachIndexed { k, ghost ->
            val partner = links.firstOrNull { (it.a == ghost.id && it.b in shown) || (it.b == ghost.id && it.a in shown) }
            val near = partner?.let { if (it.a == ghost.id) it.b else it.a }?.let(sim::position)
            val a = near?.let { kotlin.math.atan2(it.y, it.x) + (k % GHOST_FAN - 1) * GHOST_SPREAD } ?: (k * GOLDEN_ANGLE)
            sim.addNode(ghost.id, GraphPoint(ring * GHOST_DISTANCE * cos(a), ring * GHOST_DISTANCE * sin(a)))
        }
    }

    private const val BOOK_MASS = 5f
    private const val GHOST_SPREAD = 0.5f
    private const val GHOST_FAN = 3

    /** The force simulation: repulsion within a cut-off, springs on links, books held near their anchors. */
    private class Simulation {
        private val ids = ArrayList<String>()
        private val index = HashMap<String, Int>()
        private var x = FloatArray(INITIAL)
        private var y = FloatArray(INITIAL)
        private var ax = FloatArray(INITIAL)
        private var ay = FloatArray(INITIAL)
        private var mass = FloatArray(INITIAL)
        private var flags = IntArray(INITIAL)
        private val linkA = ArrayList<Int>()
        private val linkB = ArrayList<Int>()
        private val linkRest = ArrayList<Float>()
        private val linkK = ArrayList<Float>()

        fun has(id: String): Boolean = id in index

        fun addNode(id: String, at: GraphPoint, mass: Float = 1f, anchor: Boolean = false, fixed: Boolean = false) {
            if (id in index) return
            val i = ids.size
            if (i == x.size) grow()
            ids += id
            index[id] = i
            x[i] = at.x
            y[i] = at.y
            ax[i] = at.x
            ay[i] = at.y
            this.mass[i] = mass
            flags[i] = (if (anchor) ANCHORED else 0) or (if (fixed) FIXED else 0)
        }

        fun addLink(a: String, b: String, rest: Float, k: Float) {
            val i = index[a] ?: return
            val j = index[b] ?: return
            linkA += i
            linkB += j
            linkRest += rest
            linkK += k
        }

        fun position(id: String): GraphPoint = index.getValue(id).let { GraphPoint(x[it], y[it]) }

        fun run() {
            val n = ids.size
            if (n == 0) return
            val vx = FloatArray(n)
            val vy = FloatArray(n)
            // Fewer steps for big graphs: the pairs grow with the square of the words.
            val steps = (MAX_STEPS * min(1f, (STEP_BUDGET_NODES / n) * (STEP_BUDGET_NODES / n))).toInt().coerceIn(MIN_STEPS, MAX_STEPS)
            for (step in 0 until steps) {
                val cooling = 1f - step.toFloat() / steps
                repel(n, vx, vy, cooling)
                pull(vx, vy)
                move(n, vx, vy)
            }
        }

        private fun repel(n: Int, vx: FloatArray, vy: FloatArray, cooling: Float) {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val d2 = dx * dx + dy * dy + NEAR_ZERO
                    if (d2 > CUTOFF_SQUARED) continue
                    val d = sqrt(d2)
                    val f = min(MAX_PUSH, PUSH / d2) * (COOLING_FLOOR + cooling)
                    val fx = dx / d * f
                    val fy = dy / d * f
                    vx[i] -= fx / mass[i]
                    vy[i] -= fy / mass[i]
                    vx[j] += fx / mass[j]
                    vy[j] += fy / mass[j]
                }
            }
        }

        private fun pull(vx: FloatArray, vy: FloatArray) {
            for (l in linkA.indices) {
                val i = linkA[l]
                val j = linkB[l]
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val f = linkK[l] * (d - linkRest[l])
                vx[i] += dx / d * f / mass[i]
                vy[i] += dy / d * f / mass[i]
                vx[j] -= dx / d * f / mass[j]
                vy[j] -= dy / d * f / mass[j]
            }
        }

        private fun move(n: Int, vx: FloatArray, vy: FloatArray) {
            for (i in 0 until n) {
                if (flags[i] and FIXED != 0) {
                    vx[i] = 0f
                    vy[i] = 0f
                    continue
                }
                if (flags[i] and ANCHORED != 0) {
                    vx[i] += (ax[i] - x[i]) * ANCHOR_PULL
                    vy[i] += (ay[i] - y[i]) * ANCHOR_PULL
                }
                vx[i] *= DAMPING
                vy[i] *= DAMPING
                val v = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
                if (v > MAX_SPEED) {
                    vx[i] *= MAX_SPEED / v
                    vy[i] *= MAX_SPEED / v
                }
                x[i] += vx[i]
                y[i] += vy[i]
            }
        }

        private fun grow() {
            val size = x.size * 2
            x = x.copyOf(size)
            y = y.copyOf(size)
            ax = ax.copyOf(size)
            ay = ay.copyOf(size)
            mass = mass.copyOf(size)
            flags = flags.copyOf(size)
        }

        private companion object {
            const val INITIAL = 64
            const val ANCHORED = 1
            const val FIXED = 2
            const val MAX_STEPS = 320
            const val MIN_STEPS = 80
            const val STEP_BUDGET_NODES = 150f
            const val NEAR_ZERO = 0.01f
            const val CUTOFF_SQUARED = 160_000f
            const val MAX_PUSH = 9f
            const val PUSH = 2_100f
            const val COOLING_FLOOR = 0.4f
            const val ANCHOR_PULL = 0.03f
            const val DAMPING = 0.6f
            const val MAX_SPEED = 14f
        }
    }
}
