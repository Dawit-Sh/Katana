package com.example.unifiedreader.feature.library

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The book's bookmarks on its book page, after its progress: the app gives them (they live in
 * `feature:annotations`), as list items that open the book at one; `dismiss` closes the page. None
 * outside the app (previews, tests).
 */
val LocalLibraryBookPageBookmarks = staticCompositionLocalOf<(LazyListScope.(dismiss: () -> Unit) -> Unit)?> { null }
