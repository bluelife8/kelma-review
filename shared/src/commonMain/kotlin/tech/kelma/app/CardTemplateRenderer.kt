package tech.kelma.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CardTemplateRenderer {
    fun render(
        card: SyncCard,
        note: SyncNote,
        notetype: SyncNotetype?,
        media: Map<String, SyncMediaFile>,
    ): ReviewCard {
        val definition = notetype?.definition ?: JsonObject(emptyMap())
        val fieldNames = definition.fieldNames(note.fields.size)
        val fields = fieldNames.mapIndexed { index, name ->
            name to note.fields.getOrElse(index) { "" }
        }.toMap() + mapOf(
            "Tags" to note.tags.joinToString(" "),
            "Deck" to card.deckName,
            "Subdeck" to card.deckName.substringAfterLast("::"),
            "Card" to definition.template(card.ord)?.string("name").orEmpty(),
        )
        val template = definition.template(card.ord)
        val questionFormat = template?.string("qfmt")
            ?.takeIf { it.isNotBlank() }
            ?: "{{${fieldNames.firstOrNull().orEmpty()}}}"
        val answerField = fieldNames.getOrNull(1) ?: fieldNames.firstOrNull().orEmpty()
        val answerFormat = template?.string("afmt")
            ?.takeIf { it.isNotBlank() }
            ?: "{{FrontSide}}<hr id=answer>{{${answerField}}}"

        val questionHtml = renderFormat(
            format = questionFormat,
            fields = fields,
            cardOrdinal = card.ord,
            answerSide = false,
            frontSide = "",
        )
        val fullAnswer = renderFormat(
            format = answerFormat,
            fields = fields,
            cardOrdinal = card.ord,
            answerSide = true,
            frontSide = questionHtml,
        )
        val answerHtml = answerOnly(fullAnswer, questionHtml, answerFormat)
        val front = parseFace(questionHtml, media)
        val back = parseFace(answerHtml, media)

        return ReviewCard(
            id = card.cardId,
            front = front.text.ifBlank { "(empty card)" },
            back = back.text.ifBlank { "(no answer content)" },
            noteGuid = card.noteGuid,
            noteMarked = note.tags.any { it.equals("marked", ignoreCase = true) },
            frontAudio = front.audio,
            backAudio = back.audio,
            frontImages = front.images,
            backImages = back.images,
            frontBlocks = front.blocks,
            backBlocks = back.blocks,
            frontHtml = questionHtml,
            backHtml = answerHtml,
            fullAnswerHtml = fullAnswer,
            cardCss = definition.string("css").orEmpty(),
        )
    }
}

private data class RenderedFace(
    val text: String,
    val audio: List<CardMedia>,
    val images: List<CardMedia>,
    val blocks: List<CardBlock>,
)

private data class MediaToken(
    val start: Int,
    val endInclusive: Int,
    val type: String,
    val filename: String,
)

private val MustacheValue = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")
private val AnswerDivider = Regex(
    """<hr\b[^>]*\bid\s*=\s*["']?answer["']?[^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val SoundToken = Regex("""\[sound:([^]]+)]""", RegexOption.IGNORE_CASE)
private val ImageToken = Regex(
    """<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val ClozeToken = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""", RegexOption.DOT_MATCHES_ALL)

internal fun JsonObject.fieldNames(fieldCount: Int): List<String> {
    val modern = array("flds").mapNotNull {
        runCatching { it.jsonObject.string("name") }.getOrNull()
    }
    if (modern.isNotEmpty()) return modern
    val simple = array("fields").mapNotNull { element ->
        runCatching { element.jsonPrimitive.content }.getOrNull()
            ?: runCatching { element.jsonObject.string("name") }.getOrNull()
    }
    if (simple.isNotEmpty()) return simple
    return List(fieldCount) { index -> if (index == 0) "Front" else if (index == 1) "Back" else "Field${index + 1}" }
}

private fun JsonObject.template(ordinal: Int): JsonObject? {
    val templates = array("tmpls").ifEmpty { array("templates") }
    return templates.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        .firstOrNull { it.int("ord") == ordinal }
        ?: templates.getOrNull(ordinal)?.let { runCatching { it.jsonObject }.getOrNull() }
}

private fun JsonObject.array(name: String): JsonArray =
    runCatching { getValue(name).jsonArray }.getOrElse { JsonArray(emptyList()) }

private fun JsonObject.string(name: String): String? =
    runCatching { getValue(name).jsonPrimitive.content }.getOrNull()

private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()

private fun renderFormat(
    format: String,
    fields: Map<String, String>,
    cardOrdinal: Int,
    answerSide: Boolean,
    frontSide: String,
): String {
    val rendered = renderConditionalSections(format, fields)
    return MustacheValue.replace(rendered) { match ->
        val expression = match.groupValues[1].trim()
        when {
            expression.equals("FrontSide", ignoreCase = true) -> frontSide
            expression.startsWith("cloze:", ignoreCase = true) -> {
                val field = expression.substringAfter(':').trim()
                renderCloze(fields[field].orEmpty(), cardOrdinal + 1, answerSide)
            }
            else -> fields[expression.substringAfterLast(':').trim()].orEmpty()
        }
    }
}

private fun renderConditionalSections(
    format: String,
    fields: Map<String, String>,
    depth: Int = 0,
): String {
    if (depth >= 32) return format
    val output = StringBuilder(format.length)
    var cursor = 0
    while (cursor < format.length) {
        val opening = nextSectionToken(format, cursor)
        if (opening == null) {
            output.append(format, cursor, format.length)
            break
        }
        output.append(format, cursor, opening.start)
        val closing = matchingSectionClose(format, opening)
        if (closing == null) {
            output.append(format, opening.start, opening.end)
            cursor = opening.end
            continue
        }
        val field = opening.expression.drop(1).substringAfterLast(':').trim()
        val populated = fields[field].orEmpty().isNotBlank()
        val inverted = opening.expression.startsWith('^')
        if (populated.xor(inverted)) {
            output.append(
                renderConditionalSections(
                    format.substring(opening.end, closing.start),
                    fields,
                    depth + 1,
                ),
            )
        }
        cursor = closing.end
    }
    return output.toString()
}

private data class TemplateToken(val start: Int, val end: Int, val expression: String)

private fun nextSectionToken(value: String, fromIndex: Int): TemplateToken? {
    var cursor = fromIndex
    while (cursor < value.length) {
        val start = value.indexOf("{{", cursor)
        if (start < 0) return null
        val endMarker = value.indexOf("}}", start + 2)
        if (endMarker < 0) return null
        val expression = value.substring(start + 2, endMarker).trim()
        if (expression.startsWith('#') || expression.startsWith('^')) {
            return TemplateToken(start, endMarker + 2, expression)
        }
        cursor = endMarker + 2
    }
    return null
}

private fun matchingSectionClose(value: String, opening: TemplateToken): TemplateToken? {
    var cursor = opening.end
    val sections = mutableListOf(opening.expression.drop(1).trim())
    while (cursor < value.length) {
        val start = value.indexOf("{{", cursor)
        if (start < 0) return null
        val endMarker = value.indexOf("}}", start + 2)
        if (endMarker < 0) return null
        val expression = value.substring(start + 2, endMarker).trim()
        when {
            expression.startsWith('#') || expression.startsWith('^') -> sections += expression.drop(1).trim()
            expression.startsWith('/') -> {
                val name = expression.drop(1).trim()
                if (!name.equals(sections.last(), ignoreCase = true)) return null
                sections.removeLast()
                if (sections.isEmpty()) return TemplateToken(start, endMarker + 2, expression)
            }
        }
        cursor = endMarker + 2
    }
    return null
}

private fun renderCloze(value: String, target: Int, answerSide: Boolean): String =
    ClozeToken.replace(value) { match ->
        val ordinal = match.groupValues[1].toIntOrNull()
        val answer = match.groupValues[2]
        val hint = match.groupValues[3]
        when {
            ordinal != target -> answer
            answerSide -> "<span class=cloze>$answer</span>"
            hint.isNotBlank() -> "[$hint]"
            else -> "[…]"
        }
    }

private fun answerOnly(fullAnswer: String, question: String, format: String): String {
    val divider = AnswerDivider.find(fullAnswer)
    if (divider != null) return fullAnswer.substring(divider.range.last + 1)
    if (format.contains("{{FrontSide}}", ignoreCase = true) && fullAnswer.startsWith(question)) {
        return fullAnswer.removePrefix(question)
    }
    return fullAnswer
}

private fun parseFace(html: String, media: Map<String, SyncMediaFile>): RenderedFace {
    val tokens = buildList {
        SoundToken.findAll(html).forEach { match ->
            add(MediaToken(match.range.first, match.range.last, "audio", match.groupValues[1]))
        }
        ImageToken.findAll(html).forEach { match ->
            add(MediaToken(match.range.first, match.range.last, "image", match.groupValues[1]))
        }
    }.sortedBy { it.start }
    val blocks = mutableListOf<CardBlock>()
    var cursor = 0
    tokens.forEach { token ->
        addTextBlock(blocks, html.substring(cursor, token.start))
        val filename = normalizeMediaName(token.filename)
        val cardMedia = media[filename]?.let { CardMedia(filename, it.bytes) }
        if (token.type == "audio") {
            blocks += CardAudioBlock(filename, cardMedia)
        } else {
            blocks += CardImageBlock(filename, cardMedia)
        }
        cursor = token.endInclusive + 1
    }
    addTextBlock(blocks, html.substring(cursor))
    val text = blocks.filterIsInstance<CardTextBlock>().joinToString("\n") { it.text }.trim()
    return RenderedFace(
        text = text,
        audio = blocks.filterIsInstance<CardAudioBlock>().mapNotNull { it.media },
        images = blocks.filterIsInstance<CardImageBlock>().mapNotNull { it.media },
        blocks = blocks,
    )
}

private fun addTextBlock(blocks: MutableList<CardBlock>, html: String) {
    val text = html.asPlainCardText()
    val leadingLineBreak = html.startsWithVisualBreak()
    val trailingLineBreak = html.endsWithVisualBreak()
    if (text.isNotBlank() || leadingLineBreak || trailingLineBreak) {
        blocks += CardTextBlock(
            text = text,
            html = html.takeIf { containsInlineMarkup(it) },
            leadingLineBreak = leadingLineBreak,
            trailingLineBreak = trailingLineBreak,
        )
    }
}

private val LeadingHtmlBreak = Regex("""^<(?:br|div|p|li|tr|h[1-6]|blockquote)\b""", RegexOption.IGNORE_CASE)
private val TrailingHtmlBreak = Regex(
    """(?:<br\b[^>]*>|</(?:div|p|li|tr|h[1-6]|blockquote)>)$""",
    RegexOption.IGNORE_CASE,
)

private fun String.startsWithVisualBreak(): Boolean {
    val edge = trimStart(' ', '\t')
    return edge.startsWith('\n') || edge.startsWith('\r') || LeadingHtmlBreak.containsMatchIn(edge)
}

private fun String.endsWithVisualBreak(): Boolean {
    val edge = trimEnd(' ', '\t')
    return edge.endsWith('\n') || edge.endsWith('\r') || TrailingHtmlBreak.containsMatchIn(edge)
}

private fun normalizeMediaName(value: String): String = value
    .substringAfterLast('/')
    .replace("%20", " ")
    .replace("&amp;", "&")

private val ScriptElement = Regex(
    "<script\\b[^>]*>.*?</script>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val StyleElement = Regex(
    "<style\\b[^>]*>.*?</style>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HtmlLineBreak = Regex(
    "<(?:br|/p|/div|/li|/tr|/h[1-6]|/blockquote)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)
private val HtmlElement = Regex("<[^>]+>")
private val ExcessLineBreaks = Regex("\n{3,}")

internal fun String.asPlainCardText(): String =
    replace(ScriptElement, "")
        .replace(StyleElement, "")
        .replace(HtmlLineBreak, "\n")
        .replace(HtmlElement, "")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(ExcessLineBreaks, "\n\n")
        .trim()
