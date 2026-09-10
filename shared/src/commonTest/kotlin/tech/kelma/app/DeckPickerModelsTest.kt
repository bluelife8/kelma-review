package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckPickerModelsTest {
    @Test
    fun pickerIncludesRegularDecksAndEverySuperdeckLevel() {
        val options = deckPickerOptions(
            deckNames = listOf("Solo", "x::y"),
            directCardCounts = listOf("Solo" to 4, "x::y" to 2, "x::z" to 3),
        )

        assertEquals(listOf("Solo", "x", "x::y", "x::z"), options.map(DeckPickerOption::name))
        assertEquals(listOf(4, 5, 2, 3), options.map(DeckPickerOption::cardCount))
        assertEquals(listOf(0, 0, 1, 1), options.map(DeckPickerOption::depth))
    }

    @Test
    fun pickerDeduplicatesDeckNamesCaseInsensitively() {
        assertEquals(
            listOf("Languages", "Languages::French"),
            deckPickerNames(listOf("Languages::French", "languages")),
        )
    }

    @Test
    fun tieredPickerKeepsSubdecksOutOfThePrimaryDeckList() {
        val options = deckPickerOptions(listOf("Solo", "x::y", "x::y::Deep", "x::z"))

        val unselected = deckPickerTiers(options, selectedName = null)
        assertEquals(listOf("Solo", "x"), unselected.decks.map(DeckPickerOption::name))
        assertEquals(emptyList(), unselected.subdecks)

        val selected = deckPickerTiers(options, selectedName = "X::Y")
        assertEquals("x", selected.selectedDeck)
        assertEquals("x::y", selected.selectedSubdeck)
        assertEquals(
            listOf("x::y", "x::y::Deep", "x::z"),
            selected.subdecks.map(DeckPickerOption::name),
        )
    }
}
