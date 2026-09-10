package tech.kelma.app

internal data class DeckPickerOption(
    val name: String,
    val depth: Int,
    val cardCount: Int,
)

internal data class DeckPickerTiers(
    val decks: List<DeckPickerOption>,
    val selectedDeck: String?,
    val subdecks: List<DeckPickerOption>,
    val selectedSubdeck: String?,
)

/**
 * Produces one selectable row for every deck and hierarchy level. Counts from leaf decks roll up
 * to their ancestors so selecting a superdeck communicates the size of its complete subtree.
 */
internal fun deckPickerOptions(
    deckNames: Iterable<String>,
    directCardCounts: Iterable<Pair<String, Int>> = emptyList(),
): List<DeckPickerOption> {
    val canonicalNames = linkedMapOf<String, String>()
    val counts = mutableMapOf<String, Int>()

    fun addHierarchy(rawName: String): List<String> {
        if (rawName.isBlank()) return emptyList()
        return deckHierarchyNames(rawName).onEach { name ->
            val key = name.lowercase()
            if (key !in canonicalNames) canonicalNames[key] = name
        }
    }

    deckNames.forEach(::addHierarchy)
    directCardCounts.forEach { (name, count) ->
        addHierarchy(name).forEach { hierarchyName ->
            val key = hierarchyName.lowercase()
            counts[key] = counts.getOrElse(key) { 0 } + count
        }
    }

    return canonicalNames
        .values
        .sortedWith(String.CASE_INSENSITIVE_ORDER.then(naturalOrder()))
        .map { name ->
            DeckPickerOption(
                name = name,
                depth = deckHierarchyNames(name).lastIndex,
                cardCount = counts[name.lowercase()] ?: 0,
            )
        }
}

internal fun deckPickerNames(deckNames: Iterable<String>): List<String> =
    deckPickerOptions(deckNames).map(DeckPickerOption::name)

internal fun deckPickerTiers(
    options: List<DeckPickerOption>,
    selectedName: String?,
): DeckPickerTiers {
    val decks = options.filter { it.depth == 0 }
    val selectedOption = selectedName?.let { name ->
        options.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val selectedDeck = selectedOption?.let { option ->
        decks.firstOrNull { option.name.isDeckOrDescendantOf(it.name) }
    }
    val subdecks = selectedDeck?.let { deck ->
        options.filter { option ->
            !option.name.equals(deck.name, ignoreCase = true) && option.name.isDeckOrDescendantOf(deck.name)
        }
    }.orEmpty()

    return DeckPickerTiers(
        decks = decks,
        selectedDeck = selectedDeck?.name,
        subdecks = subdecks,
        selectedSubdeck = selectedOption?.takeIf { it.depth > 0 }?.name,
    )
}
