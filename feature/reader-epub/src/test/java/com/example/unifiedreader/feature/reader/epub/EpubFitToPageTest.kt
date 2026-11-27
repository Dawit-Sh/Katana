package com.example.unifiedreader.feature.reader.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFitToPageTest {
    @Test
    fun firstLineCapitalsBecomeSmallCapitalsAtTheRightSize() {
        assertTrue(EpubFitToPageScript.contains("'font-variant-caps', 'all-small-caps', 'important'"))
        assertTrue(EpubFitToPageScript.contains("if (!size) return '118%';"))
        assertTrue(EpubFitToPageScript.contains("parseFloat(m[1]) * 1.18"))
    }

    @Test
    fun longWordsTablesAndUnbreakableRunsWrap() {
        assertTrue(EpubFitToPageScript.contains("body{overflow-wrap:break-word!important}"))
        assertTrue(EpubFitToPageScript.contains("table[t].style.setProperty('width', '100%', 'important')"))
        assertTrue(EpubFitToPageScript.contains("holder.style.setProperty('text-indent', '0', 'important')"))
        assertTrue(EpubFitToPageScript.contains("found[j].style.setProperty('white-space', 'normal', 'important')"))
    }

    @Test
    fun theScriptHasNoKotlinTemplatesLeft() {
        assertFalse(EpubFitToPageScript.contains("\$Epub"))
    }
}
