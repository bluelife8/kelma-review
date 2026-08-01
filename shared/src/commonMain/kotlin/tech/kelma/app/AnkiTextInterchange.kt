package tech.kelma.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun exportNotesText(collection: SyncedCollection, deckName: String?): String {
    val cards = collection.scopedCards(deckName)
    val deckByNote = cards.groupBy(SyncCard::noteGuid).mapValues { (_, noteCards) ->
        noteCards.minWith(compareBy(SyncCard::ord, SyncCard::cardId)).deckName
    }
    val notes = collection.notes.values.filter { it.guid in deckByNote }.sortedBy(SyncNote::guid)
    val maximumFields = notes.maxOfOrNull { it.fields.size } ?: 2
    val tagsColumn = 4 + maximumFields
    return buildString {
        appendLine("#separator:tab")
        appendLine("#html:true")
        appendLine("#guid column:1")
        appendLine("#notetype column:2")
        appendLine("#deck column:3")
        appendLine("#tags column:$tagsColumn")
        notes.forEach { note ->
            val values = buildList {
                add(note.guid)
                add(collection.notetypes[note.notetypeId]?.name ?: "Basic")
                add(deckByNote.getValue(note.guid))
                addAll(note.fields)
                repeat(maximumFields - note.fields.size) { add("") }
                add(note.tags.joinToString(" "))
            }
            appendLine(values.joinToString("\t", transform = ::quoteTextField))
        }
    }
}

internal fun exportCardsText(collection: SyncedCollection, deckName: String?): String = buildString {
    appendLine("#separator:tab")
    appendLine("#html:true")
    collection.scopedCards(deckName).forEach { card ->
        val rendered = collection.reviewCard(card.cardId) ?: return@forEach
        appendLine(
            listOf(rendered.frontHtml.orEmpty(), rendered.backHtml.orEmpty())
                .joinToString("\t", transform = ::quoteTextField),
        )
    }
}

internal fun detectTextImportKind(document: InterchangeDocument): TextImportKind {
    val headers = parseAnkiText(document.bytes.decodeToString(throwOnInvalidSequence = true)).headers
    return if (headers.keys.any { it in setOf("guid column", "notetype column", "deck column", "tags column") }) {
        TextImportKind.Notes
    } else if (document.filename.substringBeforeLast('.').contains("card", ignoreCase = true)) {
        TextImportKind.Cards
    } else {
        TextImportKind.Notes
    }
}

internal fun importAnkiText(
    document: InterchangeDocument,
    kind: TextImportKind,
    targetDeck: String,
): CollectionImportPlan {
    val table = parseAnkiText(document.bytes.decodeToString(throwOnInvalidSequence = true))
    require(table.rows.isNotEmpty()) { "The text file does not contain any rows" }
    val fallbackDeck = normalizeDeckName(targetDeck)
    return when (kind) {
        TextImportKind.Notes -> table.toNotesPlan(document.filename, fallbackDeck)
        TextImportKind.Cards -> table.toCardsPlan(document.filename, fallbackDeck)
    }
}

private data class AnkiTextTable(
    val headers: Map<String, String>,
    val rows: List<List<String>>,
)

private fun AnkiTextTable.toNotesPlan(sourceName: String, fallbackDeck: String): CollectionImportPlan {
    val special = mapOf(
        "guid" to headerColumn("guid column"),
        "notetype" to headerColumn("notetype column"),
        "deck" to headerColumn("deck column"),
        "tags" to headerColumn("tags column"),
    )
    val specialIndexes = special.values.filterNotNull().toSet()
    val regularIndexes = (0 until rows.maxOf(List<String>::size)).filter { it !in specialIndexes }
    require(regularIndexes.isNotEmpty()) { "The text file has no note fields" }
    val notetypeNames = rows.map { row -> row.valueAt(special["notetype"]).ifBlank { headers["notetype"] ?: "Basic" } }
    val notetypes = notetypeNames.distinct().sorted().map { name ->
        val fieldNames = regularIndexes.mapIndexed { index, column ->
            headerColumnNames().getOrNull(column)?.ifBlank { null } ?: "Field ${index + 1}"
        }
        textNotetype(name, fieldNames)
    }
    val notetypeByName = notetypes.associateBy(ImportedNotetype::name)
    val notes = rows.mapIndexed { index, row ->
        val fields = regularIndexes.map(row::valueAt).dropLastWhile(String::isEmpty).ifEmpty { listOf("") }
            .map(::importTextField)
        val guid = row.valueAt(special["guid"]).ifBlank { stableTextGuid(sourceName, row, index) }
        val name = notetypeNames[index]
        ImportedNote(
            sourceId = index.toLong() + 1,
            guid = guid,
            notetypeId = notetypeByName.getValue(name).sourceId,
            fields = fields,
            tags = (headers["tags"].orEmpty() + " " + row.valueAt(special["tags"]))
                .split(Regex("\\s+")).filter(String::isNotBlank).distinct(),
        )
    }
    val cards = rows.mapIndexed { index, row ->
        ImportedCard(
            sourceId = index.toLong() + 1,
            noteSourceId = index.toLong() + 1,
            deckName = row.valueAt(special["deck"]).ifBlank { headers["deck"] ?: fallbackDeck },
            ordinal = 0,
        )
    }
    return CollectionImportPlan(
        sourceName = sourceName,
        decks = cards.map(ImportedCard::deckName).flatMap(::deckHierarchyNames).toSet(),
        notetypes = notetypes,
        notes = notes,
        cards = cards,
        reviews = emptyList(),
        media = emptyList(),
    )
}

private fun AnkiTextTable.toCardsPlan(sourceName: String, fallbackDeck: String): CollectionImportPlan {
    require(rows.all { it.size >= 2 }) { "Cards text requires at least question and answer columns" }
    val notes = rows.mapIndexed { index, row ->
        ImportedNote(
            sourceId = index.toLong() + 1,
            guid = stableTextGuid(sourceName, row, index),
            notetypeId = NotetypeCatalog.BasicId,
            fields = listOf(importTextField(row[0]), importTextField(row[1])),
            tags = emptyList(),
        )
    }
    return CollectionImportPlan(
        sourceName = sourceName,
        decks = deckHierarchyNames(fallbackDeck).toSet(),
        notetypes = emptyList(),
        notes = notes,
        cards = notes.map { ImportedCard(it.sourceId, it.sourceId, fallbackDeck, 0) },
        reviews = emptyList(),
        media = emptyList(),
        warnings = listOf(
            "Rendered card text was imported as Basic notes; template structure and scheduling are unavailable.",
        ),
    )
}

private fun parseAnkiText(input: String): AnkiTextTable {
    val normalized = input.removePrefix("\uFEFF")
    val headers = mutableMapOf<String, String>()
    var bodyOffset = 0
    while (bodyOffset < normalized.length) {
        val lineEnd = normalized.indexOf('\n', bodyOffset).takeIf { it >= 0 } ?: normalized.length
        val line = normalized.substring(bodyOffset, lineEnd).removeSuffix("\r")
        if (!line.startsWith('#') && line.isNotBlank()) break
        HeaderPattern.matchEntire(line)?.let { match ->
            headers[match.groupValues[1].trim().lowercase()] = match.groupValues[2].trim()
        }
        bodyOffset = if (lineEnd < normalized.length) lineEnd + 1 else lineEnd
    }
    val separator = when (headers["separator"]?.lowercase()) {
        null, "tab", "\\t" -> '\t'
        "comma" -> ','
        "semicolon" -> ';'
        "space" -> ' '
        "pipe" -> '|'
        "colon" -> ':'
        else -> headers.getValue("separator").singleOrNull()
            ?: error("Unsupported text separator")
    }
    val body = normalized.substring(bodyOffset)
    return AnkiTextTable(headers, parseDelimitedRows(body, separator).filterNot { row -> row.all(String::isBlank) })
}

private fun parseDelimitedRows(input: String, separator: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < input.length) {
        val character = input[index]
        when {
            quoted && character == '"' && input.getOrNull(index + 1) == '"' -> {
                field.append('"')
                index++
            }
            character == '"' && (quoted || field.isEmpty()) -> quoted = !quoted
            !quoted && character == separator -> {
                row += field.toString()
                field.clear()
            }
            !quoted && (character == '\n' || character == '\r') -> {
                if (character == '\r' && input.getOrNull(index + 1) == '\n') index++
                row += field.toString()
                rows += row
                row = mutableListOf()
                field.clear()
            }
            else -> field.append(character)
        }
        index++
    }
    require(!quoted) { "The text file has an unterminated quoted field" }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row
    }
    return rows
}

private fun AnkiTextTable.importTextField(value: String): String =
    if (headers["html"].equals("true", ignoreCase = true)) value else value.asEscapedCardHtml()

private fun String.asEscapedCardHtml(): String = replace("\r\n", "\n").replace('\r', '\n')
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\n", "<br>")

private fun AnkiTextTable.headerColumn(key: String): Int? = headers[key]?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

private fun AnkiTextTable.headerColumnNames(): List<String> {
    val columns = headers["columns"] ?: return emptyList()
    val separator = when (headers["separator"]?.lowercase()) {
        "comma" -> ','
        "semicolon" -> ';'
        "space" -> ' '
        "pipe" -> '|'
        "colon" -> ':'
        else -> '\t'
    }
    return parseDelimitedRows(columns, separator).firstOrNull().orEmpty()
}

private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

private fun textNotetype(name: String, fieldNames: List<String>): ImportedNotetype {
    val sourceId = stablePositiveId("text-notetype:$name:${fieldNames.joinToString("\u001f")}")
    val fields = fieldNames.ifEmpty { listOf("Front", "Back") }
    val definition = buildJsonObject {
        put("css", JsonPrimitive(""))
        put("flds", JsonArray(fields.map { field -> buildJsonObject { put("name", field) } }))
        put(
            "tmpls",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "Card 1")
                        put("ord", 0)
                        put("qfmt", "{{${fields.first()}}}")
                        put(
                            "afmt",
                            "{{FrontSide}}<hr id=answer>{{${fields.getOrElse(1) { fields.first() }}}}",
                        )
                    },
                )
            },
        )
    }
    return ImportedNotetype(sourceId, name.ifBlank { "Imported" }, definition.toString())
}

private fun stableTextGuid(sourceName: String, row: List<String>, index: Int): String {
    val digest = SchedulerHistorySha256()
        .update(sourceName.encodeToByteArray())
        .update(byteArrayOf(0))
        .update(row.joinToString("\u001f").encodeToByteArray())
        .hexDigest()
    return "text-${digest.take(24)}-${index.toString(36)}"
}

internal fun stablePositiveId(seed: String): Long {
    val digest = sha1(seed.encodeToByteArray())
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (digest[index].toLong() and 0xff) }
    return (value and Long.MAX_VALUE).coerceAtLeast(2L)
}

private fun SyncedCollection.scopedCards(deckName: String?): List<SyncCard> = cards.values
    .filter { deckName == null || it.deckName.isDeckOrDescendantOf(deckName) }
    .sortedWith(compareBy(SyncCard::deckName, SyncCard::cardId))

private fun quoteTextField(value: String): String {
    if (value.none { it == '\t' || it == '\n' || it == '\r' || it == '"' }) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

private val HeaderPattern = Regex("""#([^:]+):(.*)""")
