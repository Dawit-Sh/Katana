package com.example.unifiedreader.feature.annotations

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Where the graph is looked at from: world point (x, y) shows at (x * scale + tx, y * scale + ty) on screen. */
@Stable
internal class GraphCamera {
    var scale by mutableFloatStateOf(1f)
    var tx by mutableFloatStateOf(0f)
    var ty by mutableFloatStateOf(0f)
    var minScale: Float = DEFAULT_MIN_SCALE
    var maxScale: Float = DEFAULT_MAX_SCALE

    fun screenX(x: Float): Float = x * scale + tx

    fun screenY(y: Float): Float = y * scale + ty

    /** Zooms by [factor] around the screen point ([cx], [cy]), within the scale's limits. */
    fun zoomBy(factor: Float, cx: Float, cy: Float) {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        val f = next / scale
        tx = cx - (cx - tx) * f
        ty = cy - (cy - ty) * f
        scale = next
    }

    fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
    }

    fun set(view: CameraView) {
        scale = view.scale
        tx = view.tx
        ty = view.ty
    }

    val view: CameraView get() = CameraView(scale, tx, ty)

    companion object {
        const val DEFAULT_MIN_SCALE = 0.08f
        const val DEFAULT_MAX_SCALE = 4f
    }
}

/** One camera position, for animating between two. */
@Immutable
internal data class CameraView(val scale: Float, val tx: Float, val ty: Float) {
    fun lerp(to: CameraView, t: Float): CameraView = CameraView(
        scale = scale * (to.scale / scale).pow(t),
        tx = tx + (to.tx - tx) * t,
        ty = ty + (to.ty - ty) * t,
    )

    companion object {
        /** [bounds] filling a [width] x [height] view less [padding] all round, no closer than [maxScale]. */
        fun fit(bounds: GraphBounds, width: Float, height: Float, padding: Float, maxScale: Float): CameraView {
            val w = max(1f, width - 2 * padding)
            val h = max(1f, height - 2 * padding)
            val s = min(w / max(1f, bounds.width), h / max(1f, bounds.height)).coerceAtMost(maxScale)
            return CameraView(s, width / 2f - bounds.centerX * s, height / 2f - bounds.centerY * s)
        }
    }
}

/** The graph's colours, from the theme. */
@Immutable
internal data class GraphLook(
    val learned: Color,
    val learning: Color,
    val newStroke: Color,
    val newFill: Color,
    val edge: Color,
    val edgeHi: Color,
    val zig: Color,
    val zigHi: Color,
    val label: Color,
    val labelStrong: Color,
    val halo: Color,
    val ring: Color,
    val dust: Color,
    val bookFill: Color,
    val bookTitle: Color,
) {
    fun fill(state: WordState): Color = when (state) {
        WordState.Learned -> learned
        WordState.Learning -> learning
        WordState.New -> newFill
    }
}

/** How the graph is drawn: its colours, the screen's density and the names measured. */
internal class GraphStyle(val look: GraphLook, val density: Float, val labels: GraphLabels)

/** What shows now: the picked word, the state filter, and what Play has brought in. */
internal class GraphShowing(
    val selectedId: String?,
    val filter: WordStateFilter,
    /** Words shown so far while Play runs; null shows every word. */
    val playShown: Set<String>?,
    /** The word Play just brought in, and how far its lines have drawn (0..1). */
    val freshId: String?,
    val freshProgress: () -> Float,
)

/** One frame of the graph: what it holds, where it is looked at from, how it is drawn and what shows. */
internal class GraphFrame(
    val model: WordGraphModel,
    val camera: GraphCamera,
    val style: GraphStyle,
    showing: GraphShowing,
    /** The books whose covers the cover slot draws over the graph now (only their titles are drawn here). */
    val coversDrawnAbove: Set<String>,
) {
    val look: GraphLook get() = style.look
    val density: Float get() = style.density
    val labels: GraphLabels get() = style.labels
    val selectedId: String? = showing.selectedId
    val filter: WordStateFilter = showing.filter
    val playShown: Set<String>? = showing.playShown
    val freshId: String? = showing.freshId
    val freshProgress: () -> Float = showing.freshProgress

    val highlighted: Set<String> = selectedId?.let { id -> setOf(id) + model.synonymsOf[id].orEmpty() }.orEmpty()

    fun isShown(id: String): Boolean = playShown == null || id in playShown

    /** Faded: not among the picked word and its synonyms, or not what the state filter keeps. */
    fun isDim(id: String): Boolean =
        (selectedId != null && id !in highlighted) || model.states[id]?.let { !filter.matches(it) } == true

    /** Names show in book mode, once zoomed in far enough for every book, and on picked words always. */
    fun labelsOn(id: String): Boolean =
        model.bookId != null || camera.scale >= LABEL_SCALE || id in highlighted || id == freshId

    fun radius(id: String): Float {
        val degree = model.degree(id)
        val dp = when {
            model.bookId != null -> BOOK_MODE_RADIUS + degree * BOOK_MODE_GROWTH
            camera.scale >= LABEL_SCALE -> NEAR_RADIUS + degree * NEAR_GROWTH
            else -> FAR_RADIUS + degree * FAR_GROWTH
        }
        return min(dp, MAX_RADIUS) * density
    }

    companion object {
        const val LABEL_SCALE = 0.7f
        private const val BOOK_MODE_RADIUS = 6.5f
        private const val BOOK_MODE_GROWTH = 1.5f
        private const val NEAR_RADIUS = 4.6f
        private const val NEAR_GROWTH = 1.25f
        private const val FAR_RADIUS = 2.8f
        private const val FAR_GROWTH = 0.6f
        private const val MAX_RADIUS = 14f
    }
}

/** Names measured once and kept: the draw loop only reads them. */
internal class GraphLabels(private val measurer: TextMeasurer, private val wordStyle: TextStyle, private val bookStyle: TextStyle) {
    private val words = HashMap<String, TextLayoutResult>()
    private val books = HashMap<String, TextLayoutResult>()

    fun word(text: String): TextLayoutResult = words.getOrPut(text) { measurer.measure(text, wordStyle, maxLines = 1) }

    fun book(text: String): TextLayoutResult = books.getOrPut(text) { measurer.measure(text, bookStyle, maxLines = 1) }
}

/** How books are drawn on the graph: their size, colour and short title. */
internal object GraphBooks {
    /** The world size of a book's rectangle: bigger in book mode, where it is the middle of everything. */
    fun size(bookMode: Boolean): Size = if (bookMode) Size(BOOK_MODE_COVER_W, BOOK_MODE_COVER_H) else Size(COVER_W, COVER_H)

    /** A steady, muted colour per book for its rectangle, mixed into the theme's own. */
    fun color(title: String, base: Color): Color {
        val hue = (abs(title.hashCode()) % HUE_STEPS).toFloat() / HUE_STEPS * FULL_TURN
        val tint = Color.hsl(hue, BOOK_SATURATION, BOOK_LIGHTNESS)
        return Color(
            red = (tint.red + base.red) / 2f,
            green = (tint.green + base.green) / 2f,
            blue = (tint.blue + base.blue) / 2f,
        )
    }

    /** A long title cut down for under a small cover. */
    fun shortTitle(title: String): String =
        if (title.length <= SHORT_TITLE) title else title.take(SHORT_TITLE - 1).trimEnd() + "…"
}

private const val COVER_W = 34f
private const val COVER_H = 51f
private const val BOOK_MODE_COVER_W = 64f
private const val BOOK_MODE_COVER_H = 96f

/** Reused paths, so a frame allocates nothing. */
internal class GraphPaths {
    val plain = Path()
    val plainDim = Path()
    val plainHi = Path()
    val zig = Path()
    val zigDim = Path()
    val zigHi = Path()
    val fresh = Path()

    fun reset() {
        plain.reset()
        plainDim.reset()
        plainHi.reset()
        zig.reset()
        zigDim.reset()
        zigHi.reset()
        fresh.reset()
    }
}

/** Draws the whole graph: lines to the books, synonym zigzags, books, words and their names. */
internal fun DrawScope.drawWordGraph(frame: GraphFrame, paths: GraphPaths) {
    paths.reset()
    buildEdges(frame, paths)
    val d = frame.density
    val look = frame.look
    drawPath(paths.plainDim, look.edge.copy(alpha = look.edge.alpha * DIM_ALPHA), style = Stroke(width = EDGE_WIDTH * d))
    drawPath(paths.zigDim, look.zig.copy(alpha = look.zig.alpha * DIM_ALPHA), style = zigStroke(ZIG_WIDTH * d))
    drawPath(paths.plain, look.edge, style = Stroke(width = EDGE_WIDTH * d))
    drawPath(paths.zig, look.zig, style = zigStroke(ZIG_WIDTH * d))
    drawPath(paths.plainHi, look.edgeHi, style = Stroke(width = EDGE_HI_WIDTH * d))
    drawPath(paths.zigHi, look.zigHi, style = zigStroke(ZIG_HI_WIDTH * d))
    drawPath(paths.fresh, look.zigHi, style = zigStroke(ZIG_HI_WIDTH * d))
    drawBooks(frame)
    drawWords(frame)
}

private fun zigStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawBooks(frame: GraphFrame) {
    val model = frame.model
    val cam = frame.camera
    val book = GraphBooks.size(model.bookId != null)
    val w = book.width * cam.scale
    val h = book.height * cam.scale
    val corner = CornerRadius(BOOK_CORNER * frame.density)
    model.books.forEach { b ->
        val p = model.bookPositions[b.itemId] ?: return@forEach
        val left = cam.screenX(p.x) - w / 2f
        val top = cam.screenY(p.y) - h / 2f
        if (!onScreen(left, top, left + w, top + h)) return@forEach
        val dim = frame.selectedId != null && model.wordsById[frame.selectedId]?.book?.itemId != b.itemId &&
            frame.highlighted.none { model.wordsById[it]?.book?.itemId == b.itemId }
        val alpha = if (dim) BOOK_DIM_ALPHA else 1f
        if (b.itemId !in frame.coversDrawnAbove) {
            drawRoundRect(GraphBooks.color(b.title, frame.look.bookFill), Offset(left, top), Size(w, h), corner, alpha = alpha)
        }
        if (model.bookId != null || cam.scale >= BOOK_TITLE_SCALE) {
            val title = frame.labels.book(GraphBooks.shortTitle(b.title))
            drawText(
                textLayoutResult = title,
                color = frame.look.bookTitle,
                topLeft = Offset(cam.screenX(p.x) - title.size.width / 2f, top + h + LABEL_GAP * frame.density),
                alpha = alpha,
                shadow = Shadow(frame.look.halo, Offset.Zero, HALO_BLUR * frame.density),
            )
        }
    }
}

private fun DrawScope.drawWords(frame: GraphFrame) {
    val model = frame.model
    val cam = frame.camera
    val look = frame.look
    val d = frame.density
    // Oldest first, so the newest sit on top.
    model.playOrder.forEach { word -> drawWord(frame, word, ghost = false) }
    model.ghosts.forEach { word -> drawWord(frame, word, ghost = true) }
    frame.selectedId?.let { id ->
        val p = model.positions[id] ?: return@let
        drawCircle(look.ring, frame.radius(id) + RING_GAP * d, Offset(cam.screenX(p.x), cam.screenY(p.y)), style = Stroke(RING_WIDTH * d))
    }
}

private fun DrawScope.drawWord(frame: GraphFrame, word: SavedWord, ghost: Boolean) {
    val p = frame.model.positions[word.id]?.takeIf { ghost || frame.isShown(word.id) } ?: return
    val cam = frame.camera
    val center = Offset(cam.screenX(p.x), cam.screenY(p.y))
    val r = frame.radius(word.id)
    val reach = LABEL_ROOM * frame.density
    if (onScreen(center.x - r, center.y - r, center.x + r, center.y + r + reach)) {
        val state = frame.model.states[word.id] ?: WordState.New
        val alpha = wordAlpha(frame, word.id, ghost)
        drawDot(frame.look, state, center, r, alpha)
        if (frame.labelsOn(word.id)) drawWordLabel(frame, word, state, Offset(center.x, center.y + r), alpha)
    }
}

private fun wordAlpha(frame: GraphFrame, id: String, ghost: Boolean): Float = when {
    frame.isDim(id) -> DIM_ALPHA
    ghost -> GHOST_ALPHA
    else -> 1f
}

/** A word's circle: learned filled with a soft glow, learning filled, new hollow. */
private fun DrawScope.drawDot(look: GraphLook, state: WordState, center: Offset, r: Float, alpha: Float) {
    if (state == WordState.Learned && alpha == 1f) {
        drawCircle(look.learned, r * GLOW_OUTER, center, alpha = GLOW_OUTER_ALPHA)
        drawCircle(look.learned, r * GLOW_INNER, center, alpha = GLOW_INNER_ALPHA)
    }
    drawCircle(look.fill(state), r, center, alpha = alpha)
    if (state == WordState.New) {
        val stroke = NEW_STROKE * density
        drawCircle(look.newStroke, r - stroke / 2f, center, alpha = alpha, style = Stroke(stroke))
    }
}

/** The word's name under its circle ([under] is the circle's lowest point). */
private fun DrawScope.drawWordLabel(frame: GraphFrame, word: SavedWord, state: WordState, under: Offset, alpha: Float) {
    val label = frame.labels.word(word.word)
    val strong = state == WordState.Learned || word.id in frame.highlighted
    drawText(
        textLayoutResult = label,
        color = if (strong) frame.look.labelStrong else frame.look.label,
        topLeft = Offset(under.x - label.size.width / 2f, under.y + LABEL_GAP * frame.density),
        alpha = alpha,
        shadow = Shadow(frame.look.halo, Offset.Zero, HALO_BLUR * frame.density),
    )
}

/** What a tap on the graph lands on. */
internal object GraphHits {
    /** The word nearest a tap at ([x], [y]) within [reach] pixels, else null. */
    fun wordAt(frame: GraphFrame, x: Float, y: Float, reach: Float): String? {
        val cam = frame.camera
        var best: String? = null
        var bestDistance = Float.MAX_VALUE
        (frame.model.words + frame.model.ghosts).forEach { word ->
            if (!frame.isShown(word.id)) return@forEach
            val p = frame.model.positions[word.id] ?: return@forEach
            val distance = hypot(cam.screenX(p.x) - x, cam.screenY(p.y) - y)
            if (distance <= max(reach, frame.radius(word.id)) && distance < bestDistance) {
                best = word.id
                bestDistance = distance
            }
        }
        return best
    }

    /** The book whose rectangle or cover holds a tap at ([x], [y]), else null. */
    fun bookAt(frame: GraphFrame, x: Float, y: Float): String? {
        val cam = frame.camera
        val book = GraphBooks.size(frame.model.bookId != null)
        return frame.model.books.firstOrNull { b ->
            val p = frame.model.bookPositions[b.itemId] ?: return@firstOrNull false
            abs(cam.screenX(p.x) - x) <= book.width * cam.scale / 2f && abs(cam.screenY(p.y) - y) <= book.height * cam.scale / 2f
        }?.itemId
    }
}

private const val DIM_ALPHA = 0.16f
private const val GHOST_ALPHA = 0.55f
private const val BOOK_DIM_ALPHA = 0.4f
private const val EDGE_WIDTH = 1.05f
private const val EDGE_HI_WIDTH = 1.7f
private const val ZIG_WIDTH = 1.25f
private const val ZIG_HI_WIDTH = 1.8f
private const val BOOK_CORNER = 2f
private const val BOOK_TITLE_SCALE = 0.45f
private const val LABEL_GAP = 4f
private const val LABEL_ROOM = 24f
private const val HALO_BLUR = 6f
private const val RING_GAP = 3.5f
private const val RING_WIDTH = 1.8f
private const val NEW_STROKE = 1.5f
private const val GLOW_OUTER = 2.6f
private const val GLOW_INNER = 1.7f
private const val GLOW_OUTER_ALPHA = 0.10f
private const val GLOW_INNER_ALPHA = 0.18f
private const val HUE_STEPS = 360
private const val FULL_TURN = 360f
private const val BOOK_SATURATION = 0.32f
private const val BOOK_LIGHTNESS = 0.34f
private const val SHORT_TITLE = 22
