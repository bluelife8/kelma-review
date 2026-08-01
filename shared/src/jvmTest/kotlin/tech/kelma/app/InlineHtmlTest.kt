package tech.kelma.app

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineHtmlTest {
    @Test
    fun detectsInlineMarkup() {
        assertTrue(containsInlineMarkup("<b>bold</b>"))
        assertTrue(containsInlineMarkup("a<br>b"))
        assertFalse(containsInlineMarkup("just plain text"))
    }

    @Test
    fun rendersBoldSpanOverTheRightRange() {
        val rendered = renderInlineHtml("<b>bold</b> plain")
        assertEquals("bold plain", rendered.text)
        val bold = rendered.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals(0, bold.start)
        assertEquals(4, bold.end)
    }

    @Test
    fun decodesEntitiesAndBreaks() {
        val rendered = renderInlineHtml("a &amp; b<br>c")
        assertEquals("a & b\nc", rendered.text)
    }

    @Test
    fun stripsUnsupportedTagsButKeepsText() {
        val rendered = renderInlineHtml("<div class=\"x\">kept</div>")
        assertEquals("kept", rendered.text)
    }
}
