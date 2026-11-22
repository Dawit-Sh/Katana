package com.example.unifiedreader.feature.annotations

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fills [paths] with this frame's lines: a curve from each word to its book, a zigzag between synonyms. */
internal fun DrawScope.buildEdges(frame: GraphFrame, paths: GraphPaths) {
    bookEdges(frame, paths)
    synonymEdges(frame, paths)
}

private fun DrawScope.bookEdges(frame: GraphFrame, paths: GraphPaths) {
    val model = frame.model
    val cam = frame.camera
    model.playOrder.forEachIndexed { i, word ->
        val p = model.positions[word.id]?.takeIf { frame.isShown(word.id) } ?: return@forEachIndexed
        val b = model.bookPositions[word.book.itemId] ?: return@forEachIndexed
        val from = Offset(cam.screenX(b.x), cam.screenY(b.y))
        val to = Offset(cam.screenX(p.x), cam.screenY(p.y))
        if (!spans(from, to)) return@forEachIndexed
        val fresh = word.id == frame.freshId
        val target = when {
            fresh -> paths.fresh
            word.id == frame.selectedId -> paths.plainHi
            frame.isDim(word.id) -> paths.plainDim
            else -> paths.plain
        }
        val t = if (fresh) (frame.freshProgress() * LINE_FIRST).coerceAtMost(1f) else 1f
        curve(target, from, to, if (i % 2 == 0) CURVE_BEND else -CURVE_BEND, t)
    }
}

private fun DrawScope.synonymEdges(frame: GraphFrame, paths: GraphPaths) {
    val model = frame.model
    val cam = frame.camera
    val amp = ZIG_AMPLITUDE * frame.density
    model.links.forEach { link ->
        val a = model.positions[link.a]?.takeIf { frame.isShown(link.a) && frame.isShown(link.b) } ?: return@forEach
        val b = model.positions[link.b] ?: return@forEach
        val from = Offset(cam.screenX(a.x), cam.screenY(a.y))
        val to = Offset(cam.screenX(b.x), cam.screenY(b.y))
        if (!spans(from, to)) return@forEach
        val fresh = link.a == frame.freshId || link.b == frame.freshId
        val t = if (fresh) ((frame.freshProgress() - ZIG_DELAY) / (1f - ZIG_DELAY)).coerceIn(0f, 1f) else 1f
        if (t > 0f) zigzag(zigTarget(frame, paths, link, fresh), from, to, amp, t)
    }
}

private fun zigTarget(frame: GraphFrame, paths: GraphPaths, link: WordLink, fresh: Boolean): Path = when {
    fresh -> paths.fresh
    link.a == frame.selectedId || link.b == frame.selectedId -> paths.zigHi
    frame.isDim(link.a) || frame.isDim(link.b) -> paths.zigDim
    else -> paths.zig
}

/** Whether the box from [a] to [b] reaches the screen. */
private fun DrawScope.spans(a: Offset, b: Offset): Boolean = onScreen(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

/** Whether a box reaches the screen, with a margin for glows and names. */
internal fun DrawScope.onScreen(left: Float, top: Float, right: Float, bottom: Float): Boolean =
    right >= -OFFSCREEN && bottom >= -OFFSCREEN && left <= size.width + OFFSCREEN && top <= size.height + OFFSCREEN

/** A gentle curve from the book to the word, drawn up to [t] of the way. */
private fun curve(path: Path, from: Offset, to: Offset, bend: Float, t: Float) {
    val mx = (from.x + to.x) / 2f - (to.y - from.y) * bend
    val my = (from.y + to.y) / 2f + (to.x - from.x) * bend
    path.moveTo(from.x, from.y)
    if (t >= 1f) {
        path.quadraticTo(mx, my, to.x, to.y)
        return
    }
    for (s in 1..CURVE_STEPS) {
        val u = t * s / CURVE_STEPS
        val v = 1f - u
        path.lineTo(v * v * from.x + 2 * v * u * mx + u * u * to.x, v * v * from.y + 2 * v * u * my + u * u * to.y)
    }
}

/** The synonym line: a zigzag that tapers at both ends, drawn up to [t] of the way. */
private fun zigzag(path: Path, from: Offset, to: Offset, amp: Float, t: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = max(1f, hypot(dx, dy))
    val n = max(MIN_ZIGS, (length / (amp * ZIG_WAVELENGTH)).roundToInt())
    val px = -dy / length
    val py = dx / length
    val last = min(n - 1, (n * t).toInt())
    path.moveTo(from.x, from.y)
    for (i in 1..last) {
        val f = i.toFloat() / n
        val o = (if (i % 2 == 1) 1f else -1f) * amp * sin(PI.toFloat() * f).pow(ZIG_TAPER)
        path.lineTo(from.x + dx * f + px * o, from.y + dy * f + py * o)
    }
    if (t >= 1f) path.lineTo(to.x, to.y)
}

private const val ZIG_AMPLITUDE = 3.2f
private const val ZIG_WAVELENGTH = 2.8f
private const val ZIG_TAPER = 0.4f
private const val MIN_ZIGS = 4
private const val CURVE_BEND = 0.12f
private const val CURVE_STEPS = 16
private const val LINE_FIRST = 1.6f
private const val ZIG_DELAY = 0.45f
private const val OFFSCREEN = 80f
