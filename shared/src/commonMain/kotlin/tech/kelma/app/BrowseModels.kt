package tech.kelma.app

enum class BrowseCardState(val label: String) {
    New("New"),
    Learning("Learning"),
    Review("Review"),
    Suspended("Suspended"),
}

/** One row in the card browser, derived from a card plus its note and rendered faces. */
data class BrowseCardRow(
    val cardId: Long,
    val noteGuid: String,
    val question: String,
    val answer: String,
    val deck: String,
    val notetype: String,
    val tags: List<String>,
    val state: BrowseCardState,
    val dueMillis: Long?,
    val isLocal: Boolean,
    val createdAtMillis: Long? = null,
)

enum class BrowseSort {
    Question,
    Answer,
    Deck,
    State,
    Due,
    Created,
    Tags,
}

data class BrowseSorting(
    val field: BrowseSort = BrowseSort.Question,
    val ascending: Boolean = true,
)

internal data class BrowsePageRequest(
    val query: String,
    val sorting: BrowseSorting,
    val nowMillis: Long,
    val offset: Int,
    val limit: Int,
    val queryId: String = "",
)

internal data class BrowsePage(
    val rows: List<BrowseCardRow>,
    val totalCount: Int,
    val decks: List<Pair<String, Int>> = emptyList(),
    val tags: List<Pair<String, Int>> = emptyList(),
)

internal fun interface BrowsePageLoader {
    suspend fun load(request: BrowsePageRequest): BrowsePage
}

/** A browser token (`deck:`, `tag:`, `note:`, `created:`, `is:`, or plain text). */
sealed interface BrowseTerm {
    data class Text(val value: String) : BrowseTerm

    data class Deck(val value: String) : BrowseTerm

    data class Tag(val value: String) : BrowseTerm

    data class Notetype(val value: String) : BrowseTerm

    data class Flag(val value: String) : BrowseTerm

    data class Created(val value: String) : BrowseTerm
}

private val QueryToken = Regex("""\S+:"[^"]*"|\S+""")
private val BrowseDate = Regex("""\d{4}-\d{2}-\d{2}""")

private val StateFlagTerms = setOf("is:new", "is:learning", "is:review", "is:suspended")

fun parseBrowseQuery(raw: String): List<BrowseTerm> = QueryToken.findAll(raw.trim())
    .map { it.value }
    .toList()
    .map { token ->
        val lower = token.lowercase()
        val value = token.substringAfter(':', "").removeSurrounding("\"")
        when {
            lower.startsWith("deck:") -> BrowseTerm.Deck(value)
            lower.startsWith("tag:") -> BrowseTerm.Tag(value)
            lower.startsWith("note:") -> BrowseTerm.Notetype(value)
            lower.startsWith("is:") -> BrowseTerm.Flag(value.lowercase())
            lower.startsWith("created:") -> BrowseTerm.Created(value.lowercase())
            else -> BrowseTerm.Text(token)
        }
    }

/** Builds a qualifier token, quoting values that contain whitespace (`deck:"French verbs"`). */
fun browseQualifier(prefix: String, value: String): String =
    if (value.any(Char::isWhitespace)) "$prefix:\"$value\"" else "$prefix:$value"

/** True when [query] already contains [term] as a whole token (case-insensitive). */
fun queryHasTerm(query: String, term: String): Boolean =
    QueryToken.findAll(query.trim()).any { it.value.equals(term, ignoreCase = true) }

/**
 * Toggles [term] in [query]: removes it when present, otherwise adds it. Terms that cannot be true
 * at once replace each other — card-state flags, `deck:`, and `note:` — while `tag:`, `is:due`, and
 * `is:local` combine freely.
 */
fun toggleQueryTerm(query: String, term: String): String {
    val tokens = QueryToken.findAll(query.trim()).map { it.value }
    return if (tokens.any { it.equals(term, ignoreCase = true) }) {
        tokens.filterNot { it.equals(term, ignoreCase = true) }.joinToString(" ")
    } else {
        setQueryTerm(query, term)
    }
}

/** Adds [term], replacing any mutually exclusive qualifier already in [query]. */
fun setQueryTerm(query: String, term: String): String {
    val tokens = QueryToken.findAll(query.trim()).map { it.value }
    val lower = term.lowercase()
    val base = when {
        lower in StateFlagTerms -> tokens.filterNot { it.lowercase() in StateFlagTerms }
        lower.startsWith("deck:") -> tokens.filterNot { it.lowercase().startsWith("deck:") }
        lower.startsWith("note:") -> tokens.filterNot { it.lowercase().startsWith("note:") }
        lower.startsWith("created:") -> tokens.filterNot { it.lowercase().startsWith("created:") }
        else -> tokens
    }
    return (base + term).joinToString(" ")
}

internal fun selectedBrowseCreationDate(query: String): String? = parseBrowseQuery(query)
    .filterIsInstance<BrowseTerm.Created>()
    .map(BrowseTerm.Created::value)
    .firstOrNull { ".." !in it && parseBrowseCreatedFilter(it) is BrowseCreatedFilter.Range }

fun BrowseCardRow.matches(terms: List<BrowseTerm>, nowMillis: Long): Boolean = terms.all { term ->
    when (term) {
        is BrowseTerm.Text -> (listOf(question, answer, deck, notetype) + tags)
            .any { it.contains(term.value, ignoreCase = true) }
        is BrowseTerm.Deck -> deck.contains(term.value, ignoreCase = true)
        is BrowseTerm.Tag -> tags.any { it.equals(term.value, ignoreCase = true) }
        is BrowseTerm.Notetype -> notetype.contains(term.value, ignoreCase = true)
        is BrowseTerm.Flag -> matchesFlag(term.value, nowMillis)
        is BrowseTerm.Created -> matchesCreatedAt(term.value)
    }
}

private fun BrowseCardRow.matchesFlag(flag: String, nowMillis: Long): Boolean = when (flag) {
    "new" -> state == BrowseCardState.New
    "learning" -> state == BrowseCardState.Learning
    "review" -> state == BrowseCardState.Review
    "suspended" -> state == BrowseCardState.Suspended
    "local" -> isLocal
    "due" -> state != BrowseCardState.Suspended && (dueMillis == null || dueMillis <= nowMillis)
    else -> true
}

internal sealed interface BrowseCreatedFilter {
    data object Unknown : BrowseCreatedFilter

    data class Range(val startMillis: Long, val endExclusiveMillis: Long) : BrowseCreatedFilter

    data object Invalid : BrowseCreatedFilter
}

internal fun parseBrowseCreatedFilter(value: String): BrowseCreatedFilter {
    if (value.equals("unknown", ignoreCase = true)) return BrowseCreatedFilter.Unknown
    val separator = value.indexOf("..")
    if (separator < 0) {
        val start = parseBrowseDate(value) ?: return BrowseCreatedFilter.Invalid
        return BrowseCreatedFilter.Range(start, (start + MillisPerDay).coerceAtLeast(start))
    }
    val startText = value.substring(0, separator)
    val endText = value.substring(separator + 2)
    val start = if (startText.isBlank()) Long.MIN_VALUE else parseBrowseDate(startText)
        ?: return BrowseCreatedFilter.Invalid
    val endStart = if (endText.isBlank()) null else parseBrowseDate(endText)
        ?: return BrowseCreatedFilter.Invalid
    val endExclusive = endStart?.let { (it + MillisPerDay).coerceAtLeast(it) } ?: Long.MAX_VALUE
    return if (start < endExclusive) {
        BrowseCreatedFilter.Range(start, endExclusive)
    } else {
        BrowseCreatedFilter.Invalid
    }
}

private fun parseBrowseDate(value: String): Long? {
    if (!BrowseDate.matches(value)) return null
    val millis = rfc3339ToEpochMillis("${value}T00:00:00Z") ?: return null
    return millis.takeIf { formatDueDate(it) == value }
}

private fun BrowseCardRow.matchesCreatedAt(value: String): Boolean =
    when (val filter = parseBrowseCreatedFilter(value)) {
        BrowseCreatedFilter.Unknown -> createdAtMillis == null
        is BrowseCreatedFilter.Range -> createdAtMillis
            ?.let { it in filter.startMillis until filter.endExclusiveMillis }
            ?: false
        BrowseCreatedFilter.Invalid -> false
    }

private data class BrowseSortValue(
    val row: BrowseCardRow,
    val number: Long = 0,
    val primary: String = "",
    val secondary: String = "",
)

fun List<BrowseCardRow>.sortedForBrowse(sorting: BrowseSorting): List<BrowseCardRow> {
    if (sorting.field == BrowseSort.Created) {
        val comparator = compareBy<BrowseCardRow> { it.createdAtMillis == null }.let { knownFirst ->
            if (sorting.ascending) {
                knownFirst.thenBy { it.createdAtMillis }.thenBy { it.cardId }
            } else {
                knownFirst.thenByDescending { it.createdAtMillis }.thenByDescending { it.cardId }
            }
        }
        return sortedWith(comparator)
    }
    val values = map { row ->
        val question = row.question.lowercase()
        when (sorting.field) {
            BrowseSort.Question -> BrowseSortValue(row, primary = question)
            BrowseSort.Answer -> BrowseSortValue(row, primary = row.answer.lowercase())
            BrowseSort.Deck -> BrowseSortValue(row, primary = row.deck.lowercase(), secondary = question)
            BrowseSort.State -> BrowseSortValue(row, number = row.state.ordinal.toLong(), secondary = question)
            BrowseSort.Due -> BrowseSortValue(row, number = row.dueMillis ?: Long.MAX_VALUE, secondary = question)
            BrowseSort.Created -> error("Created sorting is handled before projection")
            BrowseSort.Tags -> BrowseSortValue(
                row,
                primary = row.tags.joinToString(",").lowercase(),
                secondary = question,
            )
        }
    }
    val ordered = values.sortedWith(
        compareBy<BrowseSortValue> { it.number }
            .thenBy { it.primary }
            .thenBy { it.secondary }
            .thenBy { it.row.cardId },
    ).map(BrowseSortValue::row)
    return if (sorting.ascending) ordered else ordered.reversed()
}

/** Builds browser rows for every card whose note is present, rendering faces for search and display. */
fun SyncedCollection.browseRows(
    schedules: Map<Long, LocalCardSchedule>,
    dueDateOverrides: Map<Long, Long> = emptyMap(),
): List<BrowseCardRow> = browseContentRows().withLocalScheduling(schedules, dueDateOverrides)

internal fun SyncedCollection.browseContentRows(): List<BrowseCardRow> =
    cards.values.mapNotNull(::browseContentRow)

internal fun SyncedCollection.browseContentRow(card: SyncCard): BrowseCardRow? {
    val note = notes[card.noteGuid] ?: return null
    val notetype = notetypes[note.notetypeId]
    val rendered = CardTemplateRenderer.render(card, note, notetype, media = emptyMap())
    return BrowseCardRow(
        cardId = card.cardId,
        noteGuid = note.guid,
        question = rendered.front,
        answer = rendered.back,
        deck = card.deckName,
        notetype = notetype?.name ?: "Basic",
        tags = note.tags,
        state = browseCardState(card.studyState, null),
        dueMillis = null,
        isLocal = card.cardId < 0,
        createdAtMillis = card.createdAtMillis(),
    )
}

internal fun List<BrowseCardRow>.withLocalScheduling(
    schedules: Map<Long, LocalCardSchedule>,
    dueDateOverrides: Map<Long, Long>,
): List<BrowseCardRow> = map { row ->
    val schedule = schedules[row.cardId]
    row.copy(
        state = if (row.state == BrowseCardState.Suspended) {
            BrowseCardState.Suspended
        } else {
            browseCardState(CardStudyState.Active, schedule)
        },
        dueMillis = dueDateOverrides[row.cardId] ?: schedule?.dueAtMillis,
    )
}

private fun browseCardState(
    studyState: CardStudyState,
    local: LocalCardSchedule?,
): BrowseCardState = when {
    studyState == CardStudyState.Suspended -> BrowseCardState.Suspended
    local?.phase in setOf(ReviewPhase.Learning, ReviewPhase.Relearning) -> BrowseCardState.Learning
    local?.phase == ReviewPhase.Review -> BrowseCardState.Review
    else -> BrowseCardState.New
}

/** Formats an epoch-millis due time as `YYYY-MM-DD` (UTC civil date). */
fun formatDueDate(epochMillis: Long): String {
    val days = epochDayAt(epochMillis) + 719_468
    val era = (if (days >= 0) days else days - 146_096) / 146_097
    val dayOfEra = days - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9
    val fullYear = if (month <= 2) year + 1 else year
    return "$fullYear-${month.twoDigits()}-${day.twoDigits()}"
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')
