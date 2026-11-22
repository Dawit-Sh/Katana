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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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

/** What one frame shows: the selection, the filter, Play's progress, and how names are drawn. */
internal class GraphFrame(
    val model: WordGraphModel,
    val camera: GraphCamera,
    val look: GraphLook,
    val density: Float,
    val selectedId: String?,
    val filter: WordStateFilter,
    /** Words shown so far while Play runs; null shows every word. */
    val playShown: Set<String>?,
    /** The word Play just brought in, and how far its lines have drawn (0..1). */
    val freshId: String?,
    val freshProgress: () -> Float,
    val labels: GraphLabels,
    /** Whether book covers are drawn by the cover slot (then only their titles are drawn here). */
    val coversDrawnAbove: Boolean,
) {
    val highlighted: Set<String> = selectedId?.let { id -> setOf(id) + model.synonymsOf[id].orEmpty() }.orEmpty()

    fun isShown(id: String): Boolean = playShown == null || id in playShown

    fun isDim(id: String): Boolean {
        if (selectedId != null && id !in highlighted) return true
        val state = model.states[id] ?: return false
        return !filter.matches(state)
    }

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

private fun DrawScope.buildEdges(frame: GraphFrame, paths: GraphPaths) {
    bookEdges(frame, paths)
    synonymEdges(frame, paths)
}

private fun DrawScope.bookEdges(frame: GraphFrame, paths: GraphPaths) {
    val model = frame.model
    val cam = frame.camera
    model.playOrder.forEachIndexed { i, word ->
        if (!frame.isShown(word.id)) return@forEachIndexed
        val p = model.positions[word.id] ?: return@forEachIndexed
        val b = model.bookPositions[word.book.itemId] ?: return@forEachIndexed
        val x0 = cam.screenX(b.x)
        val y0 = cam.screenY(b.y)
        val x1 = cam.screenX(p.x)
        val y1 = cam.screenY(p.y)
        if (!onScreen(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))) return@forEachIndexed
        val bend = if (i % 2 == 0) CURVE_BEND else -CURVE_BEND
        val target = when {
            word.id == frame.freshId -> paths.fresh
            word.id == frame.selectedId -> paths.plainHi
            frame.isDim(word.id) -> paths.plainDim
            else -> paths.plain
        }
        val t = if (word.id == frame.freshId) (frame.freshProgress() * LINE_FIRST).coerceAtMost(1f) else 1f
        curve(target, x0, y0, x1, y1, bend, t)
    }
}

private fun DrawScope.synonymEdges(frame: GraphFrame, paths: GraphPaths) {
    val model = frame.model
    val cam = frame.camera
    val amp = ZIG_AMPLITUDE * frame.density
    model.links.forEach { link ->
        if (!frame.isShown(link.a) || !frame.isShown(link.b)) return@forEach
        val a = model.positions[link.a] ?: return@forEach
        val b = model.positions[link.b] ?: return@forEach
        val x0 = cam.screenX(a.x)
        val y0 = cam.screenY(a.y)
        val x1 = cam.screenX(b.x)
        val y1 = cam.screenY(b.y)
        if (!onScreen(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))) return@forEach
        val fresh = link.a == frame.freshId || link.b == frame.freshId
        val target = when {
            fresh -> paths.fresh
            link.a == frame.selectedId || link.b == frame.selectedId -> paths.zigHi
            frame.isDim(link.a) || frame.isDim(link.b) -> paths.zigDim
            else -> paths.zig
        }
        val t = if (fresh) ((frame.freshProgress() - ZIG_DELAY) / (1f - ZIG_DELAY)).coerceIn(0f, 1f) else 1f
        if (t > 0f) zigzag(target, x0, y0, x1, y1, amp, t)
    }
}

private fun DrawScope.onScreen(left: Float, top: Float, right: Float, bottom: Float): Boolean =
    right >= -OFFSCREEN && bottom >= -OFFSCREEN && left <= size.width + OFFSCREEN && top <= size.height + OFFSCREEN

/** A gentle curve from the book to the word, drawn up to [t] of the way. */
private fun curve(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, bend: Float, t: Float) {
    val mx = (x0 + x1) / 2f - (y1 - y0) * bend
    val my = (y0 + y1) / 2f + (x1 - x0) * bend
    path.moveTo(x0, y0)
    if (t >= 1f) {
        path.quadraticTo(mx, my, x1, y1)
        return
    }
    val steps = CURVE_STEPS
    for (s in 1..steps) {
        val u = t * s / steps
        val v = 1f - u
        path.lineTo(v * v * x0 + 2 * v * u * mx + u * u * x1, v * v * y0 + 2 * v * u * my + u * u * y1)
    }
}

/** The synonym line: a zigzag that tapers at both ends, drawn up to [t] of the way. */
private fun zigzag(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, amp: Float, t: Float) {
    val dx = x1 - x0
    val dy = y1 - y0
    val length = max(1f, hypot(dx, dy))
    val n = max(MIN_ZIGS, (length / (amp * ZIG_WAVELENGTH)).roundToInt())
    val px = -dy / length
    val py = dx / length
    val last = (n * t).toInt()
    path.moveTo(x0, y0)
    for (i in 1 until n) {
        if (i > last) return
        val f = i.toFloat() / n
        val o = (if (i % 2 == 1) 1f else -1f) * amp * sin(PI.toFloat() * f).pow(ZIG_TAPER)
        path.lineTo(x0 + dx * f + px * o, y0 + dy * f + py * o)
    }
    if (t >= 1f) path.lineTo(x1, y1)
}

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
        if (!frame.coversDrawnAbove) {
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
    if (!ghost && !frame.isShown(word.id)) return
    val p = frame.model.positions[word.id] ?: return
    val cam = frame.camera
    val look = frame.look
    val d = frame.density
    val x = cam.screenX(p.x)
    val y = cam.screenY(p.y)
    val r = frame.radius(word.id)
    if (!onScreen(x - r, y - r, x + r, y + r + LABEL_ROOM * d)) return
    val state = frame.model.states[word.id] ?: WordState.New
    val alpha = when {
        frame.isDim(word.id) -> DIM_ALPHA
        ghost -> GHOST_ALPHA
        else -> 1f
    }
    val center = Offset(x, y)
    if (state == WordState.Learned && alpha == 1f) {
        drawCircle(look.learned, r * GLOW_OUTER, center, alpha = GLOW_OUTER_ALPHA)
        drawCircle(look.learned, r * GLOW_INNER, center, alpha = GLOW_INNER_ALPHA)
    }
    drawCircle(look.fill(state), r, center, alpha = alpha)
    if (state == WordState.New) drawCircle(look.newStroke, r - NEW_STROKE * d / 2f, center, alpha = alpha, style = Stroke(NEW_STROKE * d))
    if (frame.labelsOn(word.id)) {
        val label = frame.labels.word(word.word)
        drawText(
            textLayoutResult = label,
            color = if (state == WordState.Learned || word.id in frame.highlighted) look.labelStrong else look.label,
            topLeft = Offset(x - label.size.width / 2f, y + r + LABEL_GAP * d),
            alpha = alpha,
            shadow = Shadow(look.halo, Offset.Zero, HALO_BLUR * d),
        )
    }
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
private const val ZIG_AMPLITUDE = 3.2f
private const val ZIG_WAVELENGTH = 2.8f
private const val ZIG_TAPER = 0.4f
private const val MIN_ZIGS = 4
private const val CURVE_BEND = 0.12f
private const val CURVE_STEPS = 16
private const val LINE_FIRST = 1.6f
private const val ZIG_DELAY = 0.45f
private const val OFFSCREEN = 80f
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
