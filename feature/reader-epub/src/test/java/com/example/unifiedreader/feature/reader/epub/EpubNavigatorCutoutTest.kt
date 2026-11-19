package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubNavigatorCutoutTest {
    @Test
    fun `without a cutout the text sits inside the strips as before`() {
        val params = epubNavigatorContainerLayoutParams(verticalInsetPx = 84, sideInsetPx = 0)

        assertEquals(listOf(0, 84, 0, 84), params.margins())
    }

    @Test
    fun `a camera hole at the top moves the top strip below it`() {
        val params = epubNavigatorContainerLayoutParams(84, 0, EpubCutoutInsets(top = 136))

        assertEquals(listOf(0, 220, 0, 84), params.margins())
    }

    @Test
    fun `a phone turned sideways keeps the text clear of the hole on that side`() {
        val params = epubNavigatorContainerLayoutParams(84, 12, EpubCutoutInsets(left = 136))

        assertEquals(listOf(148, 84, 12, 84), params.margins())
    }

    private fun android.widget.FrameLayout.LayoutParams.margins() = listOf(leftMargin, topMargin, rightMargin, bottomMargin)
}
