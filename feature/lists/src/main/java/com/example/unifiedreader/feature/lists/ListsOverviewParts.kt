package com.example.unifiedreader.feature.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppListRowDefaults
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider

/** All's line on how books get into a list: from a book's page (Add to list), or the Add books tile here. */
@Composable
internal fun ListsHint(modifier: Modifier = Modifier) {
    AppPanel(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = AppLayout.inset, vertical = ListHintVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppListRowDefaults.markSize),
            )
            Text(
                text = "Add books from a book's page or here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Made for you, closing All: Stalled (books started and left for three weeks; opens its page, empty
 * or not) unless it is hidden, and New smart list, which asks for the library to make it from.
 */
@Composable
internal fun ListsMadeForYou(
    stalled: UserList?,
    onOpenStalled: (UserList) -> Unit,
    onNewSmartList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppPanel(modifier = modifier.fillMaxWidth()) {
        if (stalled != null) {
            AppSettingsRow(
                title = stalled.name,
                supporting = stalled.stalledLine(),
                icon = AppIcons.HourglassTop,
                onClick = { onOpenStalled(stalled) },
            )
            AppSettingsRowDivider()
        }
        AppSettingsRow(
            title = "New smart list",
            supporting = "Books that match your rules, kept up to date",
            icon = AppIcons.FilterList,
            onClick = onNewSmartList,
            showChevron = false,
            trailing = {
                Icon(imageVector = AppIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
}

private val ListHintVerticalPadding: Dp = 12.dp
