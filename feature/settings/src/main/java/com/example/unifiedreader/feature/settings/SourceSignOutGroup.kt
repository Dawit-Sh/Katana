package com.example.unifiedreader.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppTonalButton

/**
 * The last group of a connected server's page: Sign out, which asks first, then forgets the
 * server's address and sign-in on this tablet. The server leaves the side menu; its page shows
 * the sign-in form again, so it can be added back.
 */
@Composable
internal fun SourceSignOutGroup(
    sourceName: String,
    enabled: Boolean,
    onSignOut: () -> Unit,
) {
    var confirming by rememberSaveable(sourceName) { mutableStateOf(false) }
    AppSettingsGroup {
        AppSettingsRow(
            title = SignOutCopy.Title,
            supporting = SignOutCopy.row(sourceName),
            enabled = enabled,
            trailing = {
                AppTonalButton(onClick = { confirming = true }, enabled = enabled) {
                    Text(SignOutCopy.Title)
                }
            },
        )
    }
    if (confirming) {
        AppDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SignOutCopy.dialogTitle(sourceName)) },
            text = { Text(SignOutCopy.dialogBody(sourceName)) },
            confirmButton = {
                AppDialogConfirmButton(
                    text = SignOutCopy.Title,
                    destructive = true,
                    onClick = {
                        confirming = false
                        onSignOut()
                    },
                )
            },
            dismissButton = { AppDialogDismissButton(text = "Cancel", onClick = { confirming = false }) },
        )
    }
}

internal object SignOutCopy {
    const val Title = "Sign out"

    fun row(sourceName: String): String = "Removes $sourceName from this tablet. You can add it again here."

    fun dialogTitle(sourceName: String): String = "Sign out of $sourceName?"

    fun dialogBody(sourceName: String): String =
        "Katana forgets this server's address and sign-in, and $sourceName leaves the side menu. " +
            "Your reading places, highlights and the books kept on this tablet stay."
}
