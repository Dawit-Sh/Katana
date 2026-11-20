package com.example.unifiedreader.feature.dictionary

/** What the dictionary row says, and its one button. Kept pure so it can be tested. */
data class DictionaryRowCopy(
    val supporting: String,
    val button: String?,
    val progress: Float?,
    val isError: Boolean = false,
)

fun dictionaryRowCopy(state: DictionaryInstall, sizeBytes: Long): DictionaryRowCopy = when (state) {
    DictionaryInstall.NotInstalled -> DictionaryRowCopy(
        supporting = "Look up words on this device · ${DictionaryCopy.megabytes(sizeBytes)}",
        button = "Download",
        progress = null,
    )
    is DictionaryInstall.Downloading -> DictionaryRowCopy(
        supporting = "Downloading · ${DictionaryCopy.megabytes(state.downloadedBytes)} of " +
            DictionaryCopy.megabytes(state.totalBytes),
        button = null,
        progress = if (state.totalBytes > 0) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f,
    )
    is DictionaryInstall.Installed -> DictionaryRowCopy(
        supporting = "Installed · ${DictionaryCopy.megabytes(state.sizeBytes)}",
        button = "Remove",
        progress = null,
    )
    is DictionaryInstall.Failed -> DictionaryRowCopy(
        supporting = state.message,
        button = "Download",
        progress = null,
        isError = true,
    )
}
