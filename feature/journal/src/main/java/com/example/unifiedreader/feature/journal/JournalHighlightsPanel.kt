package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.ui.components.AppBookType
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppListRowDefaults
import com.example.unifiedreader.ui.components.AppTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Highlights this month: how many highlights and saved words the month holds and from which books,
 * then the latest two, each behind a bar in its colour (a highlight's own, the warm accent for a
 * word). [onOpenBookmarks] adds "See all in Bookmarks" under them; without a Bookmarks page to go
 * to, there is no link.
 */
@Composable
internal fun JournalHighlightsPanel(
    marks: JournalMonthMarks,
    title: String,
    onOpenBookmarks: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    JournalPanel(modifier = modifier) {
        JournalSectionHeader(title = title, summary = marks.countLine)
        Column(
            modifier = Modifier.padding(top = AppLayout.headingGap * 2),
            verticalArrangement = Arrangement.spacedBy(MarkGap),
        ) {
            marks.latest.forEach { line -> MarkRow(line) }
        }
        if (onOpenBookmarks != null) {
            AppTextButton(
                onClick = onOpenBookmarks,
                contentPadding = PaddingValues(),
                modifier = Modifier.padding(top = AppLayout.headingGap),
            ) {
                Text(text = "See all in Bookmarks", fontWeight = FontWeight.SemiBold)
                Icon(imageVector = AppIcons.NavigateNext, contentDescription = null, modifier = Modifier.size(AppListRowDefaults.markSize))
            }
        }
    }
}

/** A highlight's words in the book face, or a saved word, over the book it is from. */
@Composable
private fun MarkRow(line: JournalMarkLine) {
    val (bar, mainText, underText) = when (line) {
        is JournalMarkLine.Highlight -> Triple(
            Color(line.color.argb),
            "“${line.text}”",
            "${line.bookTitle} · ${markDay(line.atEpochMillis)}",
        )
        is JournalMarkLine.Word -> Triple(
            MaterialTheme.colorScheme.tertiary,
            line.word,
            "Saved word · ${line.bookTitle}",
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(MarkBarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(MarkBarWidth).fillMaxHeight().clip(CircleShape).background(bar))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mainText,
                style = if (line is JournalMarkLine.Highlight) {
                    AppBookType.title(MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal))
                } else {
                    MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = underText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "18 Nov". */
private fun markDay(epochMillis: Long): String = SimpleDateFormat("d MMM", Locale.US).format(Date(epochMillis))

private val MarkGap: Dp = 12.dp
private val MarkBarGap: Dp = 10.dp
private val MarkBarWidth: Dp = 3.dp
