package tech.kelma.app

internal data class DeckPickerOption(
    val name: String,
    val depth: Int,
    val cardCount: Int,
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
