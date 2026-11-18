package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppGroupLabel
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlin.math.roundToInt

// The pieces of a book's mark rows on the Bookmarks page: the row, its section label, the
// colour bar, the note box and the line saying where and when.

/** "Page 8 of 78 · 9%". */
internal fun bookmarkTitle(bookmark: ReaderBookmark): String {
    val place = AnnotationMarkdown.bookmarkPlace(bookmark)
    // Rounded, as the reader's own percentage is, so the two agree.
    val percent = bookmark.place.progression?.let { "${(it * PERCENT).roundToInt().coerceIn(0, PERCENT)}%" }
    return if (percent == null || place.endsWith("%")) place else "$place · $percent"
}

@Composable
internal fun SectionLabel(text: String) {
    AppGroupLabel(text = text, modifier = Modifier.padding(top = AppLayout.headingGap))
}

@Composable
internal fun MarkRow(onClick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    AppPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = AppLayout.menuInset, top = AppLayout.headingGap, bottom = AppLayout.headingGap),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.menuInset),
            verticalAlignment = Alignment.Top,
        ) {
            content()
            if (onClick != null) Spacer(Modifier.width(AppLayout.headingGap))
        }
    }
}

@Composable
internal fun ColourBar(color: Color) {
    Spacer(
        modifier = Modifier
            .width(ColourBarWidth)
            .fillMaxHeight()
            .background(color, RoundedCornerShape(UnifiedReaderThemeTokens.presentation.chipCornerRadius)),
    )
}

@Composable
internal fun NoteBox(note: String) {
    Text(
        text = note,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius),
            )
            .padding(AppLayout.headingGap),
    )
}

@Composable
internal fun MetaLine(parts: List<String>) {
    Text(
        text = parts.filter(String::isNotBlank).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val PERCENT = 100
private val ColourBarWidth = 4.dp

/** A mark's chapter, unless it only repeats the book's name (a book of one chapter). */
internal fun chapterOf(chapterTitle: String?, book: AnnotatedBook): String? =
    chapterTitle?.trim()?.takeUnless { it.isEmpty() || it.equals(book.title.trim(), ignoreCase = true) }
