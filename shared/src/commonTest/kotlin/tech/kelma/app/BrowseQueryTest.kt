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
    ) = BrowseCardRow(1L, "guid", question, answer, deck, notetype, tags, state, dueMillis, isLocal)

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
