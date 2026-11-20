package com.example.unifiedreader.feature.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppFloatingPanel
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader
import com.example.unifiedreader.ui.components.AppTonalButton

/**
 * A word the reader asked the dictionary about. [onAddToMyWords] saves it with its sentence and
 * place, keeping [meaning] (the first definition, null when there is none) as its meaning.
 */
data class DictionaryRequest(
    val word: String,
    val onAddToMyWords: (meaning: String?) -> Unit,
)

/** The words on the Dictionary card. */
object DictionaryCopy {
    const val Dictionary = "Dictionary"
    const val AddToMyWords = "Add to My words"
    const val OpenSettings = "Open Settings"
    const val Looking = "Looking it up…"
    const val RowTitle = "English dictionary"

    fun notFound(word: String) = "No definition found for ‘$word’"

    fun notInstalled(sizeBytes: Long) =
        "Download the English dictionary (${megabytes(sizeBytes)}) in Settings to look words up on this device."

    fun megabytes(bytes: Long): String = "${(bytes + MEGABYTE / 2) / MEGABYTE} MB"

    private const val MEGABYTE = 1_048_576L
}

/**
 * The Dictionary card over a book: a sheet on phones, the kit's floating panel at the end edge
 * on tablets. Shows [request]'s word, its part of speech and first senses, and Add to My words;
 * without a dictionary on this device it points to Settings. Place it in the reader's full-size
 * overlay slot.
 */
@Composable
fun ReaderDictionaryOverlay(
    request: DictionaryRequest?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dictionary = LocalDictionary.current
    val openSettings = LocalOpenDictionarySettings.current
    AppFloatingPanel(
        visible = request != null,
        placement = AppFloatingPanelPlacement.End,
        onDismissRequest = onDismiss,
        modifier = modifier,
        testTag = "dictionary-card",
    ) {
        val shown = request ?: return@AppFloatingPanel
        var result by remember(shown) { mutableStateOf<DictionaryLookup?>(null) }
        LaunchedEffect(shown, dictionary) {
            result = dictionary?.lookUp(shown.word) ?: DictionaryLookup.NotInstalled
        }
        val found = (result as? DictionaryLookup.Found)?.entry
        AppPanelHeader(title = found?.word ?: WordForms.normalize(shown.word).ifEmpty { shown.word }, onClose = onDismiss)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppLayout.inset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DictionaryBody(result = result, sizeBytes = dictionary?.downloadSizeBytes ?: DictionaryPackInfo.English.sizeBytes)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppLayout.inset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (result == DictionaryLookup.NotInstalled && openSettings != null) {
                AppButton(onClick = {
                    onDismiss()
                    openSettings()
                }) { Text(DictionaryCopy.OpenSettings) }
            }
            if (result != null) {
                AppTonalButton(onClick = {
                    shown.onAddToMyWords(found?.firstDefinition)
                    onDismiss()
                }) { Text(DictionaryCopy.AddToMyWords) }
            }
        }
    }
}

@Composable
private fun DictionaryBody(result: DictionaryLookup?, sizeBytes: Long) {
    val supporting = MaterialTheme.colorScheme.onSurfaceVariant
    when (result) {
        null -> Text(DictionaryCopy.Looking, style = MaterialTheme.typography.bodyMedium, color = supporting)
        DictionaryLookup.NotInstalled ->
            Text(DictionaryCopy.notInstalled(sizeBytes), style = MaterialTheme.typography.bodyMedium)
        is DictionaryLookup.NotFound ->
            Text(DictionaryCopy.notFound(result.word), style = MaterialTheme.typography.bodyMedium)
        is DictionaryLookup.Found -> result.entry.meanings.forEach { meaning ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = if (meaning.lemma == result.entry.word) {
                    meaning.partOfSpeech.label
                } else {
                    "${meaning.lemma} · ${meaning.partOfSpeech.label}"
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                meaning.senses.forEachIndexed { index, sense ->
                    Text("${index + 1}. ${sense.definition}", style = MaterialTheme.typography.bodyMedium)
                    sense.example?.let { example ->
                        Text(
                            "“$example”",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = supporting,
                        )
                    }
                }
            }
        }
    }
}
