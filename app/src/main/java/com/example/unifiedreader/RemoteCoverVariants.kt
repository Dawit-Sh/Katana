package com.example.unifiedreader

import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.covers.LibraryCoverVariant

internal fun LibraryCoverImageVariant.toCoverAssetVariant(): LibraryCoverVariant = when (this) {
    // The same cached thumbnail, decoded smaller.
    LibraryCoverImageVariant.GridSmall,
    LibraryCoverImageVariant.Grid -> LibraryCoverVariant.Grid
    LibraryCoverImageVariant.GridLarge -> LibraryCoverVariant.GridLarge
    LibraryCoverImageVariant.GridExtraLarge -> LibraryCoverVariant.GridExtraLarge
    LibraryCoverImageVariant.Detailed -> LibraryCoverVariant.Detail
    LibraryCoverImageVariant.ShelfPreview,
    LibraryCoverImageVariant.ShelfRow -> LibraryCoverVariant.Shelf
}
