package com.example.unifiedreader.feature.reader.epub

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    @Test
    fun `the text sits exactly between the strips when the cutout makes them differ`() {
        val context = RuntimeEnvironment.getApplication()
        val page = View(context)
        val frame = FrameLayout(context).apply {
            addView(page, epubNavigatorContainerLayoutParams(105, 0, EpubCutoutInsets(top = 142)))
        }

        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2424, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 1080, 2424)

        assertEquals(247, page.top)
        assertEquals(2424 - 105, page.bottom)
        assertEquals(0, page.left)
        assertEquals(1080, page.right)
    }

    @Test
    fun `turned sideways, the text sits between the hole and the far edge`() {
        val context = RuntimeEnvironment.getApplication()
        val page = View(context)
        val frame = FrameLayout(context).apply {
            addView(page, epubNavigatorContainerLayoutParams(105, 0, EpubCutoutInsets(left = 142)))
        }

        frame.measure(
            View.MeasureSpec.makeMeasureSpec(2424, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 2424, 1080)

        assertEquals(142, page.left)
        assertEquals(2424, page.right)
        assertEquals(105, page.top)
        assertEquals(1080 - 105, page.bottom)
    }

    private fun FrameLayout.LayoutParams.margins() = listOf(leftMargin, topMargin, rightMargin, bottomMargin)
}
