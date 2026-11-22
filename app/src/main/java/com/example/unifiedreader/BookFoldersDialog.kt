package com.example.unifiedreader

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppTextButton

/**
 * Book folders, from the Local library's "•••": the folders Katana reads books from and, when it is on, the
 * whole phone. Remove takes the folder out and its books out of the library; the files stay on the phone.
 */
@Composable
internal fun BookFoldersDialog(
    places: LocalBookPlacesShown,
    onRemoveFolder: (Uri) -> Unit,
    onStopPhone: () -> Unit,
    onAddFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book folders") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Katana shows the books in these places and looks for new ones. Removing one takes its " +
                        "books out of the library; the files stay on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (places.wholePhone) {
                    BookFolderRow(label = "All of this device", action = "Stop", onAction = onStopPhone)
                }
                places.folders.forEach { (tree, label) ->
                    BookFolderRow(label = label, action = "Remove", onAction = { onRemoveFolder(tree) })
                }
                if (!places.any) {
                    Text(text = "No folders. Add one to see its books here.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { AppDialogDismissButton(text = "Add a folder", onClick = onAddFolder) },
        confirmButton = { AppDialogConfirmButton(text = "Done", onClick = onDismiss) },
    )
}

@Composable
private fun BookFolderRow(label: String, action: String, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AppTextButton(onClick = onAction) {
            Text(text = action, fontWeight = FontWeight.SemiBold)
        }
    }
}
