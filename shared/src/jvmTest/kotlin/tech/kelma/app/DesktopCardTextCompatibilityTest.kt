package tech.kelma.app

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopCardTextCompatibilityTest {
    @Test
    fun arabicLettersUseContextualPresentationForms() {
        val source = "طالع"
        val shaped = shapeArabicForJavaFx(source)

        assertEquals("ﻃﺎﻟﻊ", shaped)
        assertEquals(source, shaped.normalizedCompatibility())
    }

    @Test
    fun lamAlefUsesTheConnectedLigatureAndPreservesMeaning() {
        val source = "بلا"
        val shaped = shapeArabicForJavaFx(source)

        assertEquals("ﺑﻼ", shaped)
        assertEquals(source, shaped.normalizedCompatibility())
    }

    @Test
    fun transparentArabicMarksDoNotBreakJoining() {
        val source = "مُرَتَّب"
        val shaped = shapeArabicForJavaFx(source)

        assertNotEquals(source, shaped)
        assertEquals(source.normalizedCompatibility(), shaped.normalizedCompatibility())
    }

    @Test
    fun shapingIsIdempotentAndLeavesOtherScriptsUntouched() {
        val source = "Moroccan ﻃﺎﻟﻊ"

        assertEquals(source, shapeArabicForJavaFx(source))
        assertEquals("Moroccan", shapeArabicForJavaFx("Moroccan"))
    }
}

private fun String.normalizedCompatibility(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
