package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.displayName

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton

/**
 * Asks before a book opens at an older place from the server ([prompt]):
 * "Grimmory has you on p. 4. Stay on p. 8 or go to p. 4?" (or Katana Desk, Kavita...). Either answer opens the book; Back or a tap
 * outside leaves it closed, and the question comes again next time.
 */
@Composable
internal fun ProgressOfferHost(
    prompt: AppProgressOfferPrompt?,
    store: ReaderSessionStore,
    onAnswer: (goToServer: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    prompt ?: return
    val item = prompt.item
    val local = store.getProgress(item)
    val pageCount = store.getSession(item)?.pageCount ?: item.pageCount
    val here = local?.let { progressPlaceLabel(it.locatorType, it.page, it.percent, pageCount) } ?: ProgressOfferCopy.Here
    val server = prompt.offer.server
    val there = progressPlaceLabel(server.locatorType, server.page, server.percent, pageCount) ?: ProgressOfferCopy.There
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title) },
        text = { Text(ProgressOfferCopy.question(from = prompt.placeFrom(), here = here, there = there)) },
        dismissButton = {
            AppDialogDismissButton(text = ProgressOfferCopy.stay(here), onClick = { onAnswer(false) })
        },
        confirmButton = {
            AppDialogConfirmButton(text = ProgressOfferCopy.go(there), onClick = { onAnswer(true) })
        },
    )
}

internal object ProgressOfferCopy {
    const val Here = "this device's place"
    const val There = "an earlier place"

    const val Server = "The server"

    fun question(from: String, here: String, there: String) = "$from has you on $there. Stay on $here or go to $there?"

    fun stay(here: String) = "Stay on $here"

    fun go(there: String) = "Go to $there"
}

/** Who has the other place: Katana Desk for a book synced with it, else the book's server by name. */
internal fun AppProgressOfferPrompt.placeFrom(): String =
    offer.from ?: item.sourceType.takeIf { it != SourceType.LOCAL }?.displayName() ?: ProgressOfferCopy.Server
