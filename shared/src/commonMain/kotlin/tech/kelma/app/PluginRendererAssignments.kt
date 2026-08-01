package tech.kelma.app

import tech.kelma.db.KelmaDatabase

enum class PluginRendererScope(val storageName: String) {
    Deck("deck"),
    NoteType("notetype"),
}

data class PluginRendererAssignment(
    val scope: PluginRendererScope,
    val targetId: String,
    val rendererId: String,
)

data class PluginRendererAssignmentState(
    val assignments: List<PluginRendererAssignment> = emptyList(),
) {
    private val decks = assignments.filter { it.scope == PluginRendererScope.Deck }
        .associate { normalizeDeckTarget(it.targetId) to it.rendererId }
    private val noteTypes = assignments.filter { it.scope == PluginRendererScope.NoteType }
        .mapNotNull { assignment -> assignment.targetId.toLongOrNull()?.let { it to assignment.rendererId } }
        .toMap()

    fun rendererFor(card: SyncCard, note: SyncNote): String? {
        var deckName = normalizeDeckTarget(card.deckName)
        while (deckName.isNotEmpty()) {
            decks[deckName]?.let { return it }
            val parentSeparator = deckName.lastIndexOf("::")
            if (parentSeparator < 0) break
            deckName = deckName.substring(0, parentSeparator)
        }
        return noteTypes[note.notetypeId]
    }
}

internal class PluginRendererAssignmentPersistence(private val database: KelmaDatabase) {
    private val queries = database.kelmaQueries

    fun load(): PluginRendererAssignmentState = PluginRendererAssignmentState(
        queries.selectPluginRendererAssignments { scope, target, renderer ->
            PluginRendererAssignment(
                scope = PluginRendererScope.entries.first { it.storageName == scope },
                targetId = target,
                rendererId = renderer,
            )
        }.executeAsList(),
    )

    fun set(
        scope: PluginRendererScope,
        targetId: String,
        rendererId: String?,
    ): PluginRendererAssignmentState {
        val normalizedTarget = normalizeTarget(scope, targetId)
        database.transaction {
            if (rendererId == null) {
                queries.deletePluginRendererAssignment(scope.storageName, normalizedTarget)
            } else {
                require(rendererId.length <= 200 && PluginQualifiedId.matches(rendererId)) {
                    "Renderer ID is invalid"
                }
                queries.upsertPluginRendererAssignment(scope.storageName, normalizedTarget, rendererId)
            }
        }
        return load()
    }

    fun renameDeck(oldName: String, newName: String) {
        val oldTarget = normalizeDeckTarget(oldName)
        val newTarget = normalizeDeckTarget(newName)
        val affected = load().assignments.filter {
            it.scope == PluginRendererScope.Deck && it.targetId.isDeckOrDescendantOf(oldTarget)
        }
        database.transaction {
            affected.forEach { assignment ->
                queries.deletePluginRendererAssignment(PluginRendererScope.Deck.storageName, assignment.targetId)
                queries.upsertPluginRendererAssignment(
                    PluginRendererScope.Deck.storageName,
                    newTarget + assignment.targetId.substring(oldTarget.length),
                    assignment.rendererId,
                )
            }
        }
    }

    fun deleteDeck(name: String) {
        val target = normalizeDeckTarget(name)
        database.transaction {
            load().assignments.filter {
                it.scope == PluginRendererScope.Deck && it.targetId.isDeckOrDescendantOf(target)
            }.forEach { assignment ->
                queries.deletePluginRendererAssignment(PluginRendererScope.Deck.storageName, assignment.targetId)
            }
        }
    }

    fun clear() {
        queries.clearPluginRendererAssignments()
    }

    private fun normalizeTarget(scope: PluginRendererScope, targetId: String): String = when (scope) {
        PluginRendererScope.Deck -> normalizeDeckTarget(targetId).also {
            require(it.isNotBlank()) { "Deck renderer target is invalid" }
        }
        PluginRendererScope.NoteType -> targetId.toLongOrNull()?.takeIf { it != 0L }?.toString()
            ?: throw IllegalArgumentException("Note-type renderer target is invalid")
    }
}

private fun normalizeDeckTarget(value: String): String = value.trim().lowercase()

private val PluginQualifiedId = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
