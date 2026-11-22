package com.example.unifiedreader.feature.annotations

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The graph's area on screen and the views it fits: everything, the book a phone starts on, one book, a picked
 * word with its synonyms. Fitting leaves room for zoom and Play: below them on a phone, beside them when [wide].
 */
internal class GraphViewport(val width: Float, val height: Float, val wide: Boolean, private val density: Float) {
    val padding: Float = FIT_PADDING * density
    private val fitWidth = if (wide) width - CONTROLS_SIDE * density else width
    private val fitHeight = if (wide) height else height - CONTROLS_INSET * density

    /** Every word and book in view. */
    fun all(model: WordGraphModel): CameraView = CameraView.fit(model.bounds, fitWidth, fitHeight, padding, FIT_MAX_SCALE)

    /** Where the graph opens: on a phone, the book saved from last with its words readable; else everything. */
    fun start(model: WordGraphModel): CameraView {
        val book = model.words.firstOrNull()?.book?.itemId
        val phoneOnEveryBook = !wide && model.bookId == null && model.books.size > 1
        return if (phoneOnEveryBook && book != null) {
            CameraView.fit(model.bookBounds(book), fitWidth, fitHeight, padding, START_MAX_SCALE)
        } else {
            all(model)
        }
    }

    /** [bookId]'s words and cover. */
    fun book(model: WordGraphModel, bookId: String): CameraView =
        CameraView.fit(model.bookBounds(bookId), fitWidth, fitHeight, padding, BOOK_ZOOM_MAX)

    /** The picked word and its synonyms in the space [card] leaves: above it on a phone, beside it when wide. */
    fun focus(model: WordGraphModel, id: String, card: IntSize): CameraView {
        val points = (listOf(id) + model.synonymsOf[id].orEmpty()).mapNotNull(model.positions::get)
        val freeWidth = if (wide) width - card.width - padding else width
        val freeHeight = if (wide) height else height - card.height
        return CameraView.fit(GraphBounds.of(points, FOCUS_MARGIN), freeWidth, freeHeight, padding, FOCUS_MAX_SCALE)
    }

    /** The smallest scale pinching may reach: a little past everything in view. */
    fun minScale(model: WordGraphModel): Float =
        (all(model).scale * MIN_SCALE_SHARE).coerceIn(GraphCamera.DEFAULT_MIN_SCALE, MIN_SCALE_CAP)

    private companion object {
        const val FIT_PADDING = 28f
        const val FIT_MAX_SCALE = 1.6f
        const val START_MAX_SCALE = 1.3f
        const val BOOK_ZOOM_MAX = 1.6f
        const val FOCUS_MARGIN = 40f
        const val FOCUS_MAX_SCALE = 1.4f
        const val MIN_SCALE_SHARE = 0.6f
        const val MIN_SCALE_CAP = 0.8f
        const val CONTROLS_INSET = 120f
        const val CONTROLS_SIDE = 200f
    }
}

/** Moves [camera] smoothly to a view; a new move, or a pinch, stops the one under way. */
internal class GraphMover(private val camera: GraphCamera, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun moveTo(view: CameraView) {
        job?.cancel()
        val from = camera.view
        job = scope.launch {
            animate(0f, 1f, animationSpec = tween(MOVE_MS, easing = FastOutSlowInEasing)) { t, _ -> camera.set(from.lerp(view, t)) }
        }
    }

    /** [factor] times closer (or further) around the middle of the area. */
    fun zoom(factor: Float, viewport: GraphViewport) =
        moveTo(camera.view.zoomed(factor, viewport.width / 2f, viewport.height / 2f, camera.maxScale, camera.minScale))

    fun stop() {
        job?.cancel()
    }

    private companion object {
        const val MOVE_MS = 450
    }
}

/** [this] view zoomed by [factor] around ([cx], [cy]), within [min]..[max]. */
internal fun CameraView.zoomed(factor: Float, cx: Float, cy: Float, max: Float, min: Float = 0f): CameraView {
    val next = (scale * factor).coerceIn(min, max)
    val f = next / scale
    return CameraView(next, cx - (cx - tx) * f, cy - (cy - ty) * f)
}
