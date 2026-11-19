package com.example.unifiedreader.feature.library

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCoverImageRequestsTest {
    @Test
    fun `libraryCoverImageRequestSize uses high quality minimum dimensions`() {
        assertEquals(
            IntSize(480, 720),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.Grid),
        )
        assertEquals(
            IntSize(720, 1080),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.GridLarge),
        )
        assertEquals(
            IntSize(960, 1440),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.GridExtraLarge),
        )
        assertEquals(
            IntSize(320, 480),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.ShelfPreview),
        )
        assertEquals(
            IntSize(960, 1440),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.Detailed),
        )
    }

    @Test
    fun `small grid covers decode the grid thumbnail at about half size`() {
        assertEquals(IntSize(240, 360), libraryCoverImageRequestSize(LibraryCoverImageVariant.GridSmall))
        // A 411 dp phone at 2.625x: 273 px for cells of 63 to 99 dp (165 to 260 px), a third of the grid's pixels.
        assertEquals(IntSize(273, 410), libraryCoverImageRequestSize(LibraryCoverImageVariant.GridSmall, density = 2.625f))
        assertEquals(IntSize(480, 720), libraryCoverImageRequestSize(LibraryCoverImageVariant.Grid, density = 2.625f))
    }

    @Test
    fun `libraryCoverImageRequestSize grows for very dense displays`() {
        assertEquals(
            IntSize(960, 1440),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.GridExtraLarge, density = 4f),
        )
        assertEquals(
            IntSize(1280, 1920),
            libraryCoverImageRequestSize(LibraryCoverImageVariant.Detailed, density = 4f),
        )
    }

    @Test
    fun `grid covers keep full quality decode policy`() {
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.Grid),
        )
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.GridLarge),
        )
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.GridExtraLarge),
        )
    }

    @Test
    fun `shelf covers keep full quality decode policy`() {
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.ShelfPreview),
        )
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.ShelfRow),
        )
    }

    @Test
    fun `detailed cover keeps hardware quality policy`() {
        assertEquals(
            LibraryCoverImageDecodePolicy(
                allowHardware = true,
                bitmapConfig = null,
            ),
            libraryCoverImageDecodePolicy(LibraryCoverImageVariant.Detailed),
        )
    }
}
