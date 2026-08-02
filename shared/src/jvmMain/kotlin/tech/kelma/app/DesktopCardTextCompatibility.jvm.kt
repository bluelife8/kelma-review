package tech.kelma.app

import java.text.Normalizer
import java.util.Base64
import javafx.scene.web.WebEngine
import org.w3c.dom.Node

/**
 * JavaFX WebView's Prism bridge skips contextual Arabic shaping and can stop
 * painting a text run after a supplementary code point. Convert Arabic to its
 * equivalent contextual forms and isolate supplementary glyph runs in spans.
 */
internal fun applyDesktopCardTextCompatibility(engine: WebEngine) {
    val document = engine.document ?: return
    val textNodes = mutableListOf<Node>()
    collectTextNodes(document.documentElement, textNodes)
    val rewrites = textNodes.mapIndexedNotNull { index, node ->
        if (node.hasExcludedAncestor()) return@mapIndexedNotNull null
        val original = node.nodeValue.orEmpty()
        val shaped = shapeArabicForJavaFx(original)
        val runs = splitSupplementaryUnicodeRuns(shaped)
        when {
            runs.size > 1 && node.parentNode?.supportsHtmlChildren() == true -> TextRewrite(index, runs)
            shaped != original -> TextRewrite(index, listOf(shaped))
            else -> null
        }
    }
    // Java-side DOM writes do not reliably invalidate WebView's Prism texture;
    // perform the prepared, ASCII-safe rewrites inside WebKit instead.
    if (rewrites.isNotEmpty()) engine.executeScript(buildTextRewriteScript(rewrites))
}

internal fun shapeArabicForJavaFx(value: String): String {
    if (value.none { it in '\u0600'..'\u08FF' }) return value

    val codePoints = value.toCodePointArray()
    val shaped = codePoints.copyOf()
    codePoints.forEachIndexed { index, codePoint ->
        val forms = ArabicShapeData.forms[codePoint] ?: return@forEachIndexed
        val previous = codePoints.previousVisibleIndex(index)
        val next = codePoints.nextVisibleIndex(index)
        val joinsPrevious = previous >= 0 &&
            codePoints[previous].canJoinNext() &&
            forms.canJoinPrevious
        val joinsNext = next >= 0 &&
            forms.canJoinNext &&
            codePoints[next].canJoinPrevious()
        shaped[index] = forms.select(joinsPrevious, joinsNext, codePoint)
    }

    codePoints.forEachIndexed { index, codePoint ->
        if (codePoint != ArabicLam || index + 1 >= codePoints.size) return@forEachIndexed
        val ligature = ArabicShapeData.lamAlefLigatures[codePoints[index + 1]] ?: return@forEachIndexed
        val previous = codePoints.previousVisibleIndex(index)
        val joinsPrevious = previous >= 0 && codePoints[previous].canJoinNext()
        shaped[index] = if (joinsPrevious) ligature.final ?: ligature.isolated else ligature.isolated
        shaped[index + 1] = RemovedCodePoint
    }

    return buildString {
        shaped.filterNot(RemovedCodePoint::equals).forEach { appendCodePoint(it) }
    }
}

private data class TextRewrite(val textNodeIndex: Int, val runs: List<String>)

private fun collectTextNodes(node: Node?, destination: MutableList<Node>) {
    if (node == null) return
    if (node.nodeType == Node.TEXT_NODE) {
        destination += node
        return
    }
    var child = node.firstChild
    while (child != null) {
        collectTextNodes(child, destination)
        child = child.nextSibling
    }
}

private fun buildTextRewriteScript(rewrites: List<TextRewrite>): String {
    val operations = rewrites.joinToString(",") { rewrite ->
        val runs = rewrite.runs.joinToString(",") { run ->
            "'${Base64.getEncoder().encodeToString(run.encodeToByteArray())}'"
        }
        "[${rewrite.textNodeIndex},[$runs]]"
    }
    return """
        (function(operations){
          var nodes=[],walker=document.createTreeWalker(document.documentElement,NodeFilter.SHOW_TEXT);
          while(walker.nextNode())nodes.push(walker.currentNode);
          var decode=function(value){
            var bytes=Uint8Array.from(atob(value),function(c){return c.charCodeAt(0);});
            return new TextDecoder('utf-8').decode(bytes);
          };
          operations.forEach(function(operation){
            var node=nodes[operation[0]];
            if(!node||!node.parentNode)return;
            var runs=operation[1].map(decode);
            if(runs.length===1){node.data=runs[0];return;}
            var fragment=document.createDocumentFragment();
            runs.forEach(function(run){
              var wrapper=document.createElement('span');
              wrapper.setAttribute('$UnicodeRunAttribute','');
              wrapper.setAttribute('style','all:unset!important;pointer-events:none!important');
              wrapper.appendChild(document.createTextNode(run));
              fragment.appendChild(wrapper);
            });
            node.parentNode.replaceChild(fragment,node);
          });
          void document.body.offsetHeight;
        })([$operations]);
    """.trimIndent()
}

private fun splitSupplementaryUnicodeRuns(value: String): List<String> {
    if (value.none(Char::isSurrogate)) return listOf(value)

    val result = mutableListOf<String>()
    var segmentStart = 0
    var segmentHasSupplementary: Boolean? = null
    var clusterStart = 0
    while (clusterStart < value.length) {
        val clusterEnd = value.unicodeClusterEnd(clusterStart)
        val clusterHasSupplementary = value.hasSupplementaryCodePoint(clusterStart, clusterEnd)
        if (segmentHasSupplementary != null && segmentHasSupplementary != clusterHasSupplementary) {
            result += value.substring(segmentStart, clusterStart)
            segmentStart = clusterStart
        }
        segmentHasSupplementary = clusterHasSupplementary
        clusterStart = clusterEnd
    }
    result += value.substring(segmentStart)
    return result
}

private fun String.unicodeClusterEnd(start: Int): Int {
    var end = start + Character.charCount(Character.codePointAt(this, start))
    while (end < length) {
        val codePoint = Character.codePointAt(this, end)
        when {
            codePoint.isClusterExtension() -> end += Character.charCount(codePoint)
            codePoint == ZeroWidthJoiner -> {
                end += Character.charCount(codePoint)
                if (end < length) {
                    end += Character.charCount(Character.codePointAt(this, end))
                }
            }
            else -> return end
        }
    }
    return end
}

private fun String.hasSupplementaryCodePoint(start: Int, end: Int): Boolean {
    var index = start
    while (index < end) {
        val codePoint = Character.codePointAt(this, index)
        if (Character.isSupplementaryCodePoint(codePoint)) return true
        index += Character.charCount(codePoint)
    }
    return false
}

private fun Int.isClusterExtension(): Boolean =
    this in EmojiVariationSelectors ||
        this in EmojiSkinToneModifiers ||
        this == CombiningEnclosingKeycap ||
        Character.getType(this) in MarkCharacterTypes

private fun Node.hasExcludedAncestor(): Boolean {
    var ancestor = parentNode
    while (ancestor != null) {
        if (ancestor.nodeType == Node.ELEMENT_NODE && ancestor.nodeName.uppercase() in ExcludedTextContainers) {
            return true
        }
        ancestor = ancestor.parentNode
    }
    return false
}

private fun Node.supportsHtmlChildren(): Boolean =
    namespaceURI.isNullOrEmpty() || namespaceURI == HtmlNamespace

private fun IntArray.previousVisibleIndex(index: Int): Int {
    var candidate = index - 1
    while (candidate >= 0 && this[candidate].isJoiningTransparent()) candidate--
    return candidate
}

private fun IntArray.nextVisibleIndex(index: Int): Int {
    var candidate = index + 1
    while (candidate < size && this[candidate].isJoiningTransparent()) candidate++
    return candidate.takeIf { it < size } ?: -1
}

private fun Int.canJoinPrevious(): Boolean = when (this) {
    ArabicTatweel -> true
    else -> ArabicShapeData.forms[this]?.canJoinPrevious == true
}

private fun Int.canJoinNext(): Boolean = when (this) {
    ArabicTatweel -> true
    else -> ArabicShapeData.forms[this]?.canJoinNext == true
}

private fun Int.isJoiningTransparent(): Boolean = when (Character.getType(this)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.FORMAT.toInt(),
    -> this != ZeroWidthNonJoiner
    else -> false
}

private fun String.toCodePointArray(): IntArray {
    val result = IntArray(codePointCount(0, length))
    var sourceIndex = 0
    var resultIndex = 0
    while (sourceIndex < length) {
        val codePoint = Character.codePointAt(this, sourceIndex)
        result[resultIndex++] = codePoint
        sourceIndex += Character.charCount(codePoint)
    }
    return result
}

private data class JoiningForms(
    val isolated: Int?,
    val final: Int?,
    val initial: Int?,
    val medial: Int?,
) {
    val canJoinPrevious: Boolean = final != null || medial != null
    val canJoinNext: Boolean = initial != null || medial != null

    fun select(joinsPrevious: Boolean, joinsNext: Boolean, fallback: Int): Int = when {
        joinsPrevious && joinsNext -> medial ?: final ?: initial ?: isolated ?: fallback
        joinsPrevious -> final ?: medial ?: isolated ?: fallback
        joinsNext -> initial ?: medial ?: isolated ?: fallback
        else -> isolated ?: fallback
    }
}

private data class LamAlefLigature(val isolated: Int, val final: Int?)

private data class MutableForms(
    var isolated: Int? = null,
    var final: Int? = null,
    var initial: Int? = null,
    var medial: Int? = null,
) {
    fun set(kind: FormKind, codePoint: Int) {
        when (kind) {
            FormKind.Isolated -> isolated = codePoint
            FormKind.Final -> final = codePoint
            FormKind.Initial -> initial = codePoint
            FormKind.Medial -> medial = codePoint
        }
    }

    fun freeze(): JoiningForms = JoiningForms(isolated, final, initial, medial)
}

private enum class FormKind { Isolated, Final, Initial, Medial }

private object ArabicShapeData {
    val forms: Map<Int, JoiningForms>
    val lamAlefLigatures: Map<Int, LamAlefLigature>

    init {
        val mutableForms = mutableMapOf<Int, MutableForms>()
        val mutableLigatures = mutableMapOf<Int, MutableForms>()
        ArabicPresentationRanges.forEach { range ->
            range.forEach { presentationCodePoint ->
                val name = Character.getName(presentationCodePoint) ?: return@forEach
                val kind = name.formKind() ?: return@forEach
                val normalized = Normalizer.normalize(
                    String(Character.toChars(presentationCodePoint)),
                    Normalizer.Form.NFKC,
                ).toCodePointArray()
                when {
                    name.startsWith("ARABIC LETTER ") && normalized.size == 1 ->
                        mutableForms.getOrPut(normalized.single(), ::MutableForms).set(kind, presentationCodePoint)
                    name.startsWith("ARABIC LIGATURE LAM WITH ALEF") &&
                        normalized.size == 2 &&
                        normalized[0] == ArabicLam &&
                        normalized[1] in LamAlefCharacters ->
                        mutableLigatures.getOrPut(normalized[1], ::MutableForms).set(kind, presentationCodePoint)
                }
            }
        }
        forms = mutableForms.mapValues { it.value.freeze() }
        lamAlefLigatures = mutableLigatures.mapNotNull { (alef, value) ->
            value.isolated?.let { alef to LamAlefLigature(it, value.final) }
        }.toMap()
    }
}

private fun String.formKind(): FormKind? = when {
    endsWith(" ISOLATED FORM") -> FormKind.Isolated
    endsWith(" FINAL FORM") -> FormKind.Final
    endsWith(" INITIAL FORM") -> FormKind.Initial
    endsWith(" MEDIAL FORM") -> FormKind.Medial
    else -> null
}

private const val ArabicLam = 0x0644
private const val ArabicTatweel = 0x0640
private const val ZeroWidthNonJoiner = 0x200C
private const val ZeroWidthJoiner = 0x200D
private const val CombiningEnclosingKeycap = 0x20E3
private const val RemovedCodePoint = -1
private const val HtmlNamespace = "http://www.w3.org/1999/xhtml"
private const val UnicodeRunAttribute = "data-kelma-unicode-run"
private val ArabicPresentationRanges = listOf(0xFB50..0xFDFF, 0xFE70..0xFEFF)
private val LamAlefCharacters = setOf(0x0622, 0x0623, 0x0625, 0x0627)
private val EmojiVariationSelectors = 0xFE0E..0xFE0F
private val EmojiSkinToneModifiers = 0x1F3FB..0x1F3FF
private val MarkCharacterTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)
private val ExcludedTextContainers = setOf("NOSCRIPT", "OPTION", "SCRIPT", "STYLE", "TEXTAREA", "TITLE")
