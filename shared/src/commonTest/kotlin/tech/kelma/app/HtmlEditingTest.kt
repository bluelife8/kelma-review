package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlEditingTest {
    @Test
    fun wrapsSelectionAndKeepsItInsideTheTags() {
        val edit = HtmlEditing.wrap("hello", 0, 5, "<b>", "</b>")
        assertEquals("<b>hello</b>", edit.text)
        assertEquals(3, edit.selectionStart)
        assertEquals(8, edit.selectionEnd)
    }

    @Test
    fun wrapWithoutSelectionPlacesCaretBetweenTags() {
        val edit = HtmlEditing.wrap("ab", 1, 1, "<i>", "</i>")
        assertEquals("a<i></i>b", edit.text)
        assertEquals(4, edit.selectionStart)
        assertEquals(4, edit.selectionEnd)
    }

    @Test
    fun listWrapsEachSelectedLine() {
        val edit = HtmlEditing.list("one\ntwo", 0, 7, ordered = false)
        assertEquals("<ul>\n  <li>one</li>\n  <li>two</li>\n</ul>", edit.text)
    }

    @Test
    fun spanAddsAnInlineColor() {
        val edit = HtmlEditing.span("x", 0, 1, "color:#e0b062")
        assertEquals("<span style=\"color:#e0b062\">x</span>", edit.text)
    }

    @Test
    fun clearFormattingStripsTagsFromSelection() {
        val edit = HtmlEditing.clearFormatting("<b>hi</b>", 0, 9)
        assertEquals("hi", edit.text)
    }

    @Test
    fun clearFormattingWithoutSelectionStripsWholeField() {
        val edit = HtmlEditing.clearFormatting("<i>all</i>", 4, 4)
        assertEquals("all", edit.text)
    }
}
