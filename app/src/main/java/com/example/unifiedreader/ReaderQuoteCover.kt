package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.LocalQuoteCover
import com.example.unifiedreader.feature.annotations.QuoteCoverLoader
import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.loadLibraryCoverBitmap

/** Gives the reader's share card the open book's cover ([item]'s), so a quote can sit on it. */
@Composable
internal fun ProvideQuoteCover(
    item: ReadableItem,
    coverImageUrlProvider: (ReadableItem, LibraryCoverImageVariant) -> String?,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val coverUrl by rememberUpdatedState(coverImageUrlProvider)
    val loader = remember(item, context) {
        QuoteCoverLoader {
            coverUrl(item, LibraryCoverImageVariant.Detailed)
                ?.takeIf(String::isNotBlank)
                ?.let { url -> loadLibraryCoverBitmap(context, url) }
        }
    }
    CompositionLocalProvider(LocalQuoteCover provides loader, content = content)
}
