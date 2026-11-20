package com.example.unifiedreader.feature.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppProgressBar
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppTonalButton
import kotlinx.coroutines.launch

/** Settings › Reading › English dictionary: Download (with its size), progress, then Installed with Remove. */
@Composable
fun DictionarySettingsRow(
    dictionary: Dictionary,
    modifier: Modifier = Modifier,
) {
    val state by dictionary.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val copy = dictionaryRowCopy(state, dictionary.downloadSizeBytes)
    AppSettingsRow(
        title = DictionaryCopy.RowTitle,
        icon = AppIcons.MenuBook,
        supporting = copy.supporting,
        supportingColor = if (copy.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        supportingContent = {
            copy.progress?.let { fraction ->
                AppProgressBar(
                    fraction = fraction,
                    height = 4.dp,
                    modifier = Modifier
                        .padding(top = RowGap)
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(),
                )
            }
            copy.button?.let { label ->
                Box(modifier = Modifier.padding(top = RowGap)) {
                    AppTonalButton(
                        onClick = {
                            if (state is DictionaryInstall.Installed) {
                                scope.launch { dictionary.remove() }
                            } else {
                                dictionary.install()
                            }
                        },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

private val RowGap = 8.dp
