package tech.kelma.app

/**
 * Pure, UI-independent text transforms backing the editor formatting toolbar. Each function takes the
 * current text plus a selection range and returns the new text with an updated selection, so the same
 * logic drives desktop and mobile and is fully unit-testable.
 */
object HtmlEditing {
    data class Edit(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** Wraps the selection in [prefix]/[suffix]; with no selection, inserts both and places the caret between. */
    fun wrap(text: String, start: Int, end: Int, prefix: String, suffix: String): Edit {
        val (s, e) = normalizeRange(text, start, end)
        val selected = text.substring(s, e)
        val newText = text.substring(0, s) + prefix + selected + suffix + text.substring(e)
        return if (selected.isEmpty()) {
            val caret = s + prefix.length
            Edit(newText, caret, caret)
        } else {
            Edit(newText, s + prefix.length, e + prefix.length)
        }
    }

    /** Replaces the selection with [value] and places the caret after it. */
    fun insert(text: String, start: Int, end: Int, value: String): Edit {
        val (s, e) = normalizeRange(text, start, end)
        val newText = text.substring(0, s) + value + text.substring(e)
        val caret = s + value.length
        return Edit(newText, caret, caret)
    }

    /** Wraps the selection in an inline span carrying [style], e.g. `color:#e0b062`. */
    fun span(text: String, start: Int, end: Int, style: String): Edit =
        wrap(text, start, end, "<span style=\"$style\">", "</span>")

    /** Wraps the selected block in a `text-align` div. */
    fun align(text: String, start: Int, end: Int, value: String): Edit =
        wrap(text, start, end, "<div style=\"text-align:$value\">", "</div>")

    /** Converts the selected lines into an ordered or unordered HTML list. */
    fun list(text: String, start: Int, end: Int, ordered: Boolean): Edit {
        val (s, e) = normalizeRange(text, start, end)
        val selected = text.substring(s, e)
        val lines = if (selected.isEmpty()) listOf("") else selected.split("\n")
        val tag = if (ordered) "ol" else "ul"
        val body = lines.joinToString("\n") { "  <li>${it.trim()}</li>" }
        val block = "<$tag>\n$body\n</$tag>"
        val newText = text.substring(0, s) + block + text.substring(e)
        return Edit(newText, s, s + block.length)
    }

    /** Removes HTML tags from the selection (or the whole field when nothing is selected). */
    fun clearFormatting(text: String, start: Int, end: Int): Edit {
        val (rawStart, rawEnd) = normalizeRange(text, start, end)
        val s = if (rawStart == rawEnd) 0 else rawStart
        val e = if (rawStart == rawEnd) text.length else rawEnd
        val stripped = text.substring(s, e).replace(TagPattern, "")
        val newText = text.substring(0, s) + stripped + text.substring(e)
        return Edit(newText, s, s + stripped.length)
    }

    private fun normalizeRange(text: String, start: Int, end: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return if (a <= b) a to b else b to a
    }

    private val TagPattern = Regex("<[^>]+>")
}
