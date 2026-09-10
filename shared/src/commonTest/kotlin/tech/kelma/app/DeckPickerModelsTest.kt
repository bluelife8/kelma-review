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
}
