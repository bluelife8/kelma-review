package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseQueryTest {
    private val now = 1_000_000_000_000L

    private fun row(
        question: String = "bonjour",
        answer: String = "hello",
        deck: String = "French",
        tags: List<String> = listOf("verbs"),
        state: BrowseCardState = BrowseCardState.New,
        dueMillis: Long? = null,
        isLocal: Boolean = false,
        notetype: String = "Basic",
        createdAtMillis: Long? = null,
    ) = BrowseCardRow(
        1L,
        "guid",
        question,
        answer,
        deck,
        notetype,
        tags,
        state,
        dueMillis,
        isLocal,
        createdAtMillis,
    )

    private fun matches(query: String, row: BrowseCardRow): Boolean =
        row.matches(parseBrowseQuery(query), now)

    @Test
    fun plainTextMatchesQuestionAnswerDeckAndTags() {
        assertTrue(matches("bonj", row()))
        assertTrue(matches("HELL", row()))
        assertTrue(matches("french", row()))
        assertTrue(matches("verbs", row()))
        assertFalse(matches("spanish", row()))
    }

    @Test
    fun multipleTermsAreCombinedWithAnd() {
        assertTrue(matches("french verbs", row()))
        assertFalse(matches("french nouns", row()))
    }

    @Test
    fun deckTagAndNoteQualifiersFilterPrecisely() {
        assertTrue(matches("deck:fren", row()))
        assertFalse(matches("deck:spanish", row()))
        assertTrue(matches("tag:verbs", row()))
        assertFalse(matches("tag:nouns", row()))
        assertTrue(matches("note:basic", row()))
        assertFalse(matches("note:cloze", row()))
    }

    @Test
    fun stateFlagsMatchCardState() {
        assertTrue(matches("is:new", row(state = BrowseCardState.New)))
        assertTrue(matches("is:learning", row(state = BrowseCardState.Learning)))
        assertTrue(matches("is:review", row(state = BrowseCardState.Review)))
        assertTrue(matches("is:suspended", row(state = BrowseCardState.Suspended)))
        assertFalse(matches("is:review", row(state = BrowseCardState.New)))
    }

    @Test
    fun dueFlagUsesTheCurrentTime() {
        assertTrue(matches("is:due", row(state = BrowseCardState.New)))
        assertTrue(matches("is:due", row(state = BrowseCardState.Review, dueMillis = now - 1)))
        assertFalse(matches("is:due", row(state = BrowseCardState.Review, dueMillis = now + 60_000)))
        assertFalse(matches("is:due", row(state = BrowseCardState.Suspended)))
    }

    @Test
    fun localFlagMatchesOnlyLocalCards() {
        assertTrue(matches("is:local", row(isLocal = true)))
        assertFalse(matches("is:local", row(isLocal = false)))
    }

    @Test
    fun createdQualifierSupportsDatesRangesOpenBoundsAndUnknown() {
        val januarySecond = row(createdAtMillis = 1_704_153_600_000L)
        assertTrue(matches("created:2024-01-02", januarySecond))
        assertTrue(matches("created:2024-01-01..2024-01-31", januarySecond))
        assertTrue(matches("created:..2024-01-02", januarySecond))
        assertTrue(matches("created:2024-01-02..", januarySecond))
        assertFalse(matches("created:2024-02-01", januarySecond))
        assertTrue(matches("created:unknown", row()))
        assertFalse(matches("created:unknown", januarySecond))
        assertFalse(matches("created:2024-02-30", januarySecond))
    }

    @Test
    fun sortingOrdersAndReverses() {
        val rows = listOf(
            row(question = "charlie", deck = "B"),
            row(question = "alpha", deck = "C").copy(cardId = 2L),
            row(question = "bravo", deck = "A").copy(cardId = 3L),
        )
        val byQuestion = rows.sortedForBrowse(BrowseSorting(BrowseSort.Question))
        assertEquals(listOf("alpha", "bravo", "charlie"), byQuestion.map { it.question })
        val byDeckDesc = rows.sortedForBrowse(BrowseSorting(BrowseSort.Deck, ascending = false))
        assertEquals(listOf("C", "B", "A"), byDeckDesc.map { it.deck })

        val dated = listOf(
            row(question = "unknown"),
            row(question = "newer", createdAtMillis = 2_000L).copy(cardId = 2L),
            row(question = "older", createdAtMillis = 1_000L).copy(cardId = 3L),
        )
        assertEquals(
            listOf("older", "newer", "unknown"),
            dated.sortedForBrowse(BrowseSorting(BrowseSort.Created)).map { it.question },
        )
        assertEquals(
            listOf("newer", "older", "unknown"),
            dated.sortedForBrowse(BrowseSorting(BrowseSort.Created, ascending = false)).map { it.question },
        )
    }

    @Test
    fun parsesQuotedQualifiersAsSingleTokens() {
        val terms = parseBrowseQuery("""deck:"French verbs" tag:greeting""")
        assertEquals(BrowseTerm.Deck("French verbs"), terms[0])
        assertEquals(BrowseTerm.Tag("greeting"), terms[1])
        assertTrue(matches("deck:\"French verbs\"", row(deck = "French verbs")))
    }

    @Test
    fun toggleAddsThenRemovesTheSameTerm() {
        assertEquals("is:new", toggleQueryTerm("", "is:new"))
        assertEquals("", toggleQueryTerm("is:new", "is:new"))
        assertEquals("tag:a is:new", toggleQueryTerm("tag:a", "is:new"))
        assertEquals("is:new", toggleQueryTerm("tag:a is:new", "tag:a"))
    }

    @Test
    fun toggleReplacesContradictoryTermsWithinAGroup() {
        assertEquals("is:learning", toggleQueryTerm("is:new", "is:learning"))
        assertEquals("is:due deck:French", toggleQueryTerm("is:due deck:Spanish", "deck:French"))
        assertEquals("tag:a note:Cloze", toggleQueryTerm("tag:a note:Basic", "note:Cloze"))
        assertEquals(
            "is:new created:2024-02-01",
            toggleQueryTerm("is:new created:2024-01-01", "created:2024-02-01"),
        )
    }

    @Test
    fun settingCreationDateReplacesTheDateWithoutTogglingItOff() {
        assertEquals(
            "is:new created:2024-01-02",
            setQueryTerm("is:new created:2024-01-01", "created:2024-01-02"),
        )
        assertEquals("created:2024-01-02", setQueryTerm("created:2024-01-02", "created:2024-01-02"))
        assertEquals("2024-01-02", selectedBrowseCreationDate("is:new created:2024-01-02"))
        assertEquals(null, selectedBrowseCreationDate("created:2024-01-01..2024-01-31"))
        assertEquals(null, selectedBrowseCreationDate("created:unknown"))
    }

    @Test
    fun toggleKeepsIndependentQualifiers() {
        assertEquals("is:new is:due", toggleQueryTerm("is:new", "is:due"))
        assertEquals("tag:a tag:b", toggleQueryTerm("tag:a", "tag:b"))
        assertEquals("is:local is:suspended", toggleQueryTerm("is:local", "is:suspended"))
    }

    @Test
    fun qualifierQuotesValuesContainingSpaces() {
        assertEquals("deck:\"French verbs\"", browseQualifier("deck", "French verbs"))
        assertEquals("tag:verbs", browseQualifier("tag", "verbs"))
        assertTrue(queryHasTerm("is:new deck:\"French verbs\"", browseQualifier("deck", "French verbs")))
        assertFalse(queryHasTerm("deck:french2", "deck:french"))
    }

    @Test
    fun dueDateFormatsAsCivilDate() {
        assertEquals("2024-01-01", formatDueDate(1_704_067_200_000L))
        assertEquals("2000-02-29", formatDueDate(951_782_400_000L))
    }
}
