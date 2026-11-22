package com.example.unifiedreader.feature.annotations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.appGutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My words as a graph: each book with the words saved from it around it, words of the same meaning joined by a
 * zigzag. [MyWordsState.bookId] picks one book (its cover in the middle) or every book. Pinch to zoom, drag to
 * move, tap a word for its card, tap a book to go to its words; Play brings the words in as they were saved.
 * [coveredBooks] are the books [bookCover] can draw; the rest stay plain rectangles.
 */
@Composable
internal fun WordGraphPane(
    state: MyWordsState,
    words: List<SavedWord>,
    senseGroups: Map<String, Set<Long>>,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    bookCover: MarkedBookCover?,
    modifier: Modifier = Modifier,
    coveredBooks: Set<String> = emptySet(),
) {
    val built by produceState<WordGraphModel?>(null, words, senseGroups, state.bookId) {
        value = withContext(Dispatchers.Default) { buildWordGraph(words, senseGroups, state.bookId) }
    }
    // Clipped: lines, covers and names past the edge never draw over the header.
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val model = built ?: return@BoxWithConstraints
        val density = LocalDensity.current.density
        val wide = maxWidth >= WIDE_MIN && maxWidth > maxHeight || maxWidth >= SIDE_CARD_MIN
        val viewport = GraphViewport(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), wide, density)
        val camera = remember { GraphCamera() }
        val scope = rememberCoroutineScope()
        val mover = remember(camera, scope) { GraphMover(camera, scope) }
        LaunchedEffect(model.bookId, viewport.width, viewport.height) {
            camera.minScale = viewport.minScale(model)
            camera.set(viewport.start(model))
        }
        LaunchedEffect(model) {
            if (state.graphSelected != null && state.graphSelected !in model.wordsById) state.graphSelected = null
        }
        val play = rememberGraphPlay(model)
        GraphLayers(model, state, camera, mover, viewport, play, bookCover, coveredBooks)
        GraphOverlays(model, state, mover, viewport, play, meanings, actions, maxHeight)
    }
}

/** The ground, the graph itself with its gestures, and the covers over it. */
@Composable
private fun GraphLayers(
    model: WordGraphModel,
    state: MyWordsState,
    camera: GraphCamera,
    mover: GraphMover,
    viewport: GraphViewport,
    play: GraphPlay,
    bookCover: MarkedBookCover?,
    coveredBooks: Set<String>,
) {
    val density = LocalDensity.current.density
    val look = rememberGraphLook()
    val style = rememberGraphStyle(model, look, density)
    val coverBase = GraphBooks.size(model.bookId != null)
    val coversShown by remember(model, bookCover, coveredBooks) {
        derivedStateOf { bookCover != null && coveredBooks.isNotEmpty() && coverBase.height * camera.scale >= COVER_MIN_HEIGHT * density }
    }
    val showing = GraphShowing(state.graphSelected, state.stateFilter, play.shown, play.freshId) { play.fresh.value }
    val frame = remember(model, style, showing.selectedId, showing.filter, showing.playShown, showing.freshId, coversShown, coveredBooks) {
        GraphFrame(model, camera, style, showing, coversDrawnAbove = if (coversShown) coveredBooks else emptySet())
    }
    val current by rememberUpdatedState(frame)
    val paths = remember { GraphPaths() }
    Box(modifier = Modifier.fillMaxSize()) {
        GraphGround(camera = camera, look = look, modifier = Modifier.fillMaxSize())
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        mover.stop()
                        camera.zoomBy(zoom, centroid.x, centroid.y)
                        camera.panBy(pan.x, pan.y)
                    }
                }
                .pointerInput(camera) { detectTapGestures { at -> onGraphTap(current, state, mover, viewport, at) } }
                .semantics { contentDescription = WordGraphCopy.description(model) },
        ) { drawWordGraph(frame, paths) }
        if (coversShown && bookCover != null) {
            GraphCovers(model.books.filter { it.itemId in coveredBooks }, frame, bookCover, coverBase, density)
        }
    }
}

/** A tap: a word opens its card (again closes it), a book zooms to its words, anywhere else closes the card. */
private fun onGraphTap(frame: GraphFrame, state: MyWordsState, mover: GraphMover, viewport: GraphViewport, at: Offset) {
    val word = GraphHits.wordAt(frame, at.x, at.y, TAP_REACH * frame.density)
    val book = if (word == null && frame.model.bookId == null) GraphHits.bookAt(frame, at.x, at.y) else null
    when {
        word != null -> state.graphSelected = if (state.graphSelected == word) null else word
        book != null -> mover.moveTo(viewport.book(frame.model, book))
        else -> state.graphSelected = null
    }
}

/** Zoom and Play at the corner, and the picked word's card (a sheet on a phone, at the side when wide). */
@Composable
private fun BoxScope.GraphOverlays(
    model: WordGraphModel,
    state: MyWordsState,
    mover: GraphMover,
    viewport: GraphViewport,
    play: GraphPlay,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    areaHeight: Dp,
) {
    val selected = state.graphSelected?.let(model.wordsById::get)
    // Back closes a word's card before it leaves the page.
    BackHandler(enabled = selected != null) { state.graphSelected = null }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(state.graphSelected, cardSize, model) {
        val id = state.graphSelected
        if (id != null && cardSize != IntSize.Zero) mover.moveTo(viewport.focus(model, id, cardSize))
    }
    // The card covers the corner while it is open: zoom and Play come back when it closes.
    if (selected == null) {
        GraphControls(
            play = play,
            onZoomIn = { mover.zoom(ZOOM_STEP, viewport) },
            onZoomOut = { mover.zoom(1f / ZOOM_STEP, viewport) },
            onFit = { mover.moveTo(viewport.all(model)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = appGutter(), bottom = AppLayout.contentBottom),
        )
    }
    if (selected != null) {
        val placement = if (viewport.wide) {
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = AppLayout.contentTop, end = appGutter())
        } else {
            Modifier.align(Alignment.BottomCenter)
        }
        WordGraphCard(
            word = selected,
            model = model,
            meaning = meaningLine(selected, meanings),
            onClose = { state.graphSelected = null },
            onOpen = { actions.open(selected) },
            onPick = { id -> state.graphSelected = id },
            side = viewport.wide,
            areaHeight = areaHeight,
            modifier = placement.onSizeChanged { cardSize = it },
        )
    }
}

@Composable
private fun rememberGraphStyle(model: WordGraphModel, look: GraphLook, density: Float): GraphStyle {
    val measurer = rememberTextMeasurer()
    val wordStyle = TextStyle(fontFamily = rememberQuoteFontFamily(), fontSize = if (model.bookId != null) BOOK_LABEL_SP else LABEL_SP)
    val bookStyle = MaterialTheme.typography.labelSmall
    return remember(measurer, wordStyle, bookStyle, look, density) {
        GraphStyle(look, density, GraphLabels(measurer, wordStyle, bookStyle))
    }
}

/** The ground under the graph: a soft glow in the middle, faint dust that drifts with the view, a darker edge. */
@Composable
private fun GraphGround(camera: GraphCamera, look: GraphLook, modifier: Modifier = Modifier) {
    val dust = remember { dustPoints() }
    Box(
        modifier = modifier.drawWithCache {
            val glow = Brush.radialGradient(
                colors = listOf(look.ring.copy(alpha = GLOW_ALPHA), Color.Transparent),
                center = Offset(size.width / 2f, size.height * GLOW_HEIGHT),
                radius = size.maxDimension * GLOW_REACH,
            )
            val vignette = Brush.radialGradient(
                colors = listOf(Color.Transparent, look.halo.copy(alpha = VIGNETTE_ALPHA)),
                center = size.center,
                radius = size.maxDimension * VIGNETTE_REACH,
            )
            onDrawBehind {
                drawRect(glow)
                val w = size.width
                val h = size.height
                for (i in dust.indices step 2) {
                    val x = ((dust[i] * w + camera.tx * PARALLAX) % w + w) % w
                    val y = ((dust[i + 1] * h + camera.ty * PARALLAX) % h + h) % h
                    drawCircle(look.dust, DUST_RADIUS * density, Offset(x, y))
                }
                drawRect(vignette)
            }
        },
    )
}

private fun dustPoints(): FloatArray {
    val random = java.util.Random(DUST_SEED)
    return FloatArray(DUST_COUNT * 2) { random.nextFloat() }
}

/** The books' real covers once they are big enough to see, moved and scaled with the view without recomposing. */
@Composable
private fun GraphCovers(books: List<AnnotatedBook>, frame: GraphFrame, bookCover: MarkedBookCover, base: Size, density: Float) {
    val model = frame.model
    val camera = frame.camera
    val baseW = base.width * COVER_BASE
    val baseH = base.height * COVER_BASE
    books.forEach { book ->
        val at = model.bookPositions[book.itemId] ?: return@forEach
        val dim = frame.selectedId != null && frame.highlighted.none { model.wordsById[it]?.book?.itemId == book.itemId }
        key(book.itemId) {
            Box(
                modifier = Modifier
                    .size((baseW / density).dp, (baseH / density).dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        translationX = camera.screenX(at.x) - baseW / 2f
                        translationY = camera.screenY(at.y) - baseH / 2f
                        scaleX = camera.scale / COVER_BASE
                        scaleY = camera.scale / COVER_BASE
                        alpha = if (dim) COVER_DIM_ALPHA else 1f
                    },
            ) { bookCover(book, Modifier.fillMaxSize()) }
        }
    }
}

/** Play the links: which words are in so far, the one just in and how far its lines have drawn. */
internal class GraphPlay(
    val fresh: Animatable<Float, AnimationVector1D>,
) {
    var index: Int? by mutableStateOf(null)
    var playing: Boolean by mutableStateOf(false)
    var speed: Int by mutableIntStateOf(1)
    var shown: Set<String>? by mutableStateOf(null)
    var freshId: String? by mutableStateOf(null)
    var total: Int = 0
    var dateOf: (Int) -> Long? = { null }

    fun toggle() {
        playing = !playing
    }

    fun nextSpeed() {
        speed = when (speed) {
            1 -> 2
            2 -> SPEED_FAST
            else -> 1
        }
    }

    /** Play has run to the end: every word shows again. */
    fun finish() {
        playing = false
        index = null
        shown = null
        freshId = null
    }
}

@Composable
private fun rememberGraphPlay(model: WordGraphModel): GraphPlay {
    val play = remember(model) { GraphPlay(Animatable(1f)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val order = model.playOrder
    play.total = order.size
    play.dateOf = { i -> order.getOrNull(i - 1)?.createdAtEpochMillis }
    LaunchedEffect(play, play.playing, play.speed) {
        if (!play.playing) return@LaunchedEffect
        var i = (play.index ?: 0).takeIf { it < order.size } ?: 0
        while (i < order.size) {
            // Waits while Katana is off screen: Play goes on where it was when it comes back.
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            i += 1
            play.index = i
            play.shown = order.take(i).mapTo(HashSet(), SavedWord::id)
            play.freshId = order[i - 1].id
            play.fresh.snapTo(0f)
            launch { play.fresh.animateTo(1f, tween(FRESH_MS / play.speed, easing = LinearEasing)) }
            delay(STEP_MS / play.speed)
        }
        delay(STEP_MS / play.speed)
        play.finish()
    }
    return play
}

@Composable
private fun rememberGraphLook(): GraphLook {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < HALF
    return remember(c, dark) {
        GraphLook(
            learned = c.primary,
            learning = c.tertiary,
            newStroke = c.onSurfaceVariant,
            newFill = c.surface,
            edge = c.outlineVariant,
            edgeHi = c.outline,
            zig = c.onSurfaceVariant.copy(alpha = ZIG_ALPHA),
            zigHi = c.onSurface,
            label = c.onSurfaceVariant,
            labelStrong = c.onSurface,
            halo = c.background,
            ring = c.onSurface,
            dust = c.onSurface.copy(alpha = if (dark) DUST_ALPHA_DARK else DUST_ALPHA_LIGHT),
            bookFill = c.surfaceContainerHighest,
            bookTitle = c.onSurfaceVariant,
        )
    }
}

private val WIDE_MIN: Dp = 600.dp
private val SIDE_CARD_MIN: Dp = 840.dp
private val LABEL_SP = 12.sp
private val BOOK_LABEL_SP = 13.sp
private const val ZOOM_STEP = 1.6f
private const val TAP_REACH = 22f
private const val COVER_MIN_HEIGHT = 36f
private const val COVER_BASE = 2f
private const val COVER_DIM_ALPHA = 0.4f
private const val STEP_MS = 1_000L
private const val FRESH_MS = 900
private const val SPEED_FAST = 4
private const val HALF = 0.5f
private const val ZIG_ALPHA = 0.55f
private const val DUST_ALPHA_DARK = 0.30f
private const val DUST_ALPHA_LIGHT = 0.16f
private const val GLOW_ALPHA = 0.07f
private const val GLOW_HEIGHT = 0.45f
private const val GLOW_REACH = 0.6f
private const val VIGNETTE_ALPHA = 0.55f
private const val VIGNETTE_REACH = 0.75f
private const val PARALLAX = 0.15f
private const val DUST_RADIUS = 0.7f
private const val DUST_COUNT = 70
private const val DUST_SEED = 1121L
