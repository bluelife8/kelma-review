package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckListHierarchyTest {
    private val decks = listOf(
        deck("Languages"),
        deck("Languages::French"),
        deck("Languages::French::Verbs"),
        deck("Languages::German"),
        deck("Other"),
    )

    @Test
    fun rowsDescribeDeckHierarchy() {
        val rows = deckListRows(decks, emptySet())

        assertEquals(listOf(0, 1, 2, 1, 0), rows.map(DeckListRow::depth))
        assertTrue(rows[0].hasChildren)
        assertTrue(rows[1].hasChildren)
        assertFalse(rows[2].hasChildren)
        assertFalse(rows.any(DeckListRow::isCollapsed))
    }

    @Test
    fun collapsedDeckHidesItsEntireSubtree() {
        val rows = deckListRows(decks, setOf("Languages"))

        assertEquals(listOf("Languages", "Other"), rows.map { it.deck.name })
        assertTrue(rows.first().isCollapsed)
    }

    @Test
    fun nestedCollapseRemainsEffectiveWhenItsParentExpands() {
        val rows = deckListRows(decks, setOf("Languages::French"))

        assertEquals(
            listOf("Languages", "Languages::French", "Languages::German", "Other"),
            rows.map { it.deck.name },
        )
        assertTrue(rows[1].isCollapsed)
    }
}

private fun deck(name: String) = DeckSummary(
    id = name,
    name = name,
    cards = emptyList(),
    newCount = 0,
    learningCount = 0,
    dueCount = 0,
)
