package tech.kelma.app

data class DeckSummary(
    val id: String,
    val name: String,
    val cards: List<ReviewCard>,
    val newCount: Int,
    val learningCount: Int,
    val dueCount: Int,
    val pendingChanges: PendingDeckChanges = PendingDeckChanges(),
    val queueLoaded: Boolean = true,
)

internal fun aggregatePendingDeckChanges(
    vararg sources: Map<String, PendingDeckChanges>,
): Map<String, PendingDeckChanges> = buildMap {
    sources.forEach { source ->
        source.forEach { (deckName, changes) ->
            deckHierarchyNames(deckName).forEach { parentName ->
                put(parentName, get(parentName)?.mergedWith(changes) ?: changes)
            }
        }
    }
}
