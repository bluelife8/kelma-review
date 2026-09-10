package tech.kelma.app

internal data class DeckListRow(
    val deck: DeckSummary,
    val depth: Int,
    val hasChildren: Boolean,
    val isCollapsed: Boolean,
)

internal fun deckListRows(
    decks: List<DeckSummary>,
    collapsedDeckIds: Set<String>,
): List<DeckListRow> {
    val normalizedCollapsedIds = collapsedDeckIds.mapTo(mutableSetOf(), String::lowercase)
    val collapsedNames = decks
        .filter { it.id.lowercase() in normalizedCollapsedIds }
        .mapTo(mutableSetOf()) { it.name.lowercase() }
    val parentNames = decks
        .flatMap { deckHierarchyNames(it.name).dropLast(1) }
        .mapTo(mutableSetOf(), String::lowercase)

    return decks.mapNotNull { deck ->
        val hierarchy = deckHierarchyNames(deck.name)
        val hidden = hierarchy.dropLast(1).any { it.lowercase() in collapsedNames }
        if (hidden) {
            null
        } else {
            DeckListRow(
                deck = deck,
                depth = hierarchy.lastIndex,
                hasChildren = deck.name.lowercase() in parentNames,
                isCollapsed = deck.id.lowercase() in normalizedCollapsedIds,
            )
        }
    }
}
