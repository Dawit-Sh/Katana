package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.unifiedreader.ui.components.AppBarSwitch
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.LocalAppToastState

/**
 * What the My words page's bar holds: on the list, the menu button and Export (the words of the
 * book on the filter, or every word, as Markdown); in the review, Back and its title. The bar names
 * no screen: the menu already says where you are.
 */
@Composable
fun MyWordsBar(
    state: MyWordsState,
    words: List<SavedWord>?,
    actions: MyWordsActions,
    menuButton: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val toasts = LocalAppToastState.current
    val save = rememberMarkdownSaver { saved ->
        toasts?.show(
            if (saved) {
                AppToastMessage(text = BookmarksCopy.Exported, icon = AppToastIcon.Done)
            } else {
                AppToastMessage(text = BookmarksCopy.ExportFailed, icon = AppToastIcon.Error)
            },
        )
    }
    AppBarSwitch(targetState = state.page, modifier = modifier.fillMaxWidth(), label = "myWordsBar") { page ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (page) {
                MyWordsPage.Words -> {
                    menuButton?.invoke()
                    Spacer(Modifier.weight(1f))
                    if (!words.isNullOrEmpty()) {
                        AppTonalButton(onClick = { save { actions.export(MyWordsList(words).inBook(state.bookId)) } }) {
                            Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(SmallIcon))
                            Spacer(Modifier.width(AppLayout.headingGap))
                            Text(MyWordsCopy.Export)
                        }
                    }
                }
                MyWordsPage.Review -> {
                    AppIconButton(onClick = { state.back() }) {
                        Icon(imageVector = AppIcons.ArrowBack, contentDescription = MyWordsCopy.Back)
                    }
                    Text(
                        text = BookmarksCopy.ReviewTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
