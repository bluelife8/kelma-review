package tech.kelma.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration

private val InlineMarkup = Regex(
    "</?(b|strong|i|em|u|sup|sub|span|ul|ol|li|div|p|br)\\b",
    RegexOption.IGNORE_CASE,
)
private val Token = Regex("<[^>]+>|[^<]+")
private val TagName = Regex("</?\\s*([a-zA-Z0-9]+)")
private val StyleAttr = Regex("style\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
private val BlockTags = setOf("div", "p", "li")

/** True when [html] carries formatting we can render as styled text; otherwise plain text is used. */
fun containsInlineMarkup(html: String): Boolean = InlineMarkup.containsMatchIn(html)

/** Renders a limited, safe subset of inline HTML (bold, italic, underline, sup/sub, color) as styled text. */
fun renderInlineHtml(html: String): AnnotatedString {
    val built = buildAnnotatedString {
        val open = ArrayDeque<Pair<String, Boolean>>()
        Token.findAll(html).forEach { match ->
            val token = match.value
            if (!token.startsWith("<")) {
                append(decodeEntities(token))
                return@forEach
            }
            val name = TagName.find(token)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            when {
                name == "br" -> append("\n")
                token.startsWith("</") -> {
                    if (open.isNotEmpty() && open.last().first == name) {
                        val (_, styled) = open.removeLast()
                        if (styled) pop()
                    }
                    if (name in BlockTags) append("\n")
                }
                else -> {
                    val style = inlineStyle(name, token)
                    if (style != null) {
                        pushStyle(style)
                        open.addLast(name to true)
                    } else {
                        open.addLast(name to false)
                    }
                    if (name == "li") append("\u2022 ")
                }
            }
        }
    }
    return built.trimmed()
}

private fun inlineStyle(name: String, rawTag: String): SpanStyle? = when (name) {
    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript)
    "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript)
    "span" -> spanColors(rawTag)
    else -> null
}

private fun spanColors(rawTag: String): SpanStyle? {
    val style = StyleAttr.find(rawTag)?.groupValues?.get(1) ?: return null
    var color: Color? = null
    var background: Color? = null
    style.split(";").forEach { declaration ->
        val property = declaration.substringBefore(":").trim().lowercase()
        val value = declaration.substringAfter(":", "").trim()
        when (property) {
            "color" -> color = parseCssColor(value)
            "background-color", "background" -> background = parseCssColor(value)
        }
    }
    if (color == null && background == null) return null
    return SpanStyle(
        color = color ?: Color.Unspecified,
        background = background ?: Color.Unspecified,
    )
}

private val NamedColors = mapOf(
    "red" to "ff0000", "green" to "008000", "blue" to "0000ff", "yellow" to "ffd400",
    "orange" to "ffa500", "purple" to "800080", "white" to "ffffff", "black" to "000000",
    "gray" to "808080", "grey" to "808080",
)

private fun parseCssColor(value: String): Color? {
    val v = value.trim().lowercase()
    val hex = when {
        v.startsWith("#") -> v.removePrefix("#")
        else -> NamedColors[v] ?: return null
    }
    val rgb = when (hex.length) {
        3 -> hex.map { c -> "$c$c" }.joinToString("")
        6 -> hex
        else -> return null
    }
    val packed = rgb.toIntOrNull(16) ?: return null
    return Color((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF)
}

private fun decodeEntities(text: String): String = text
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

private fun AnnotatedString.trimmed(): AnnotatedString {
    if (isEmpty()) return this
    var start = 0
    while (start < length && this[start].isWhitespace()) start++
    var end = length
    while (end > start && this[end - 1].isWhitespace()) end--
    return if (start == 0 && end == length) this else subSequence(start, end)
}
