package tech.kelma.app

import tech.kelma.db.KelmaDatabase

internal class LocalDeckOperations(private val database: KelmaDatabase) {
    private val queries = database.kelmaQueries

    fun create(name: String, nowMillis: Long) {
        val normalized = normalizeDeckName(name)
        database.transaction {
            require(visibleDeckNames().none { it.equals(normalized, ignoreCase = true) }) {
                "A deck with this name already exists"
            }
            deckHierarchyNames(normalized).forEach { queries.insertLocalDeck(it, nowMillis) }
            queries.upsertLocalDeckSync(normalized, "upsert", null, "", nowMillis)
        }
    }

    fun rename(oldName: String, requestedName: String, nowMillis: Long) {
        val old = normalizeDeckName(oldName)
        val new = normalizeDeckName(requestedName)
        if (old == new) return
        require(!new.startsWith("$old::", ignoreCase = true)) { "A deck cannot be moved inside itself" }
        database.transaction {
            val overrides = deckOverrides()
            val canonical = canonicalNameForDisplay(old, overrides)
            val localNames = queries.selectLocalDecks().executeAsList()
            val affectedLocal = localNames.filter { it.isDeckOrDescendantOf(old) }
            require(canonical != null || affectedLocal.isNotEmpty()) { "This deck no longer exists" }

            val affectedVisible = visibleDeckNames().filter { it.isDeckOrDescendantOf(old) }
            val unavailable = visibleDeckNames() - affectedVisible.toSet()
            affectedVisible.map { new + it.substring(old.length) }.forEach { target ->
                require(unavailable.none { it.equals(target, ignoreCase = true) }) {
                    "A deck with this name already exists"
                }
            }

            renameLocalRows(old, new, affectedLocal, nowMillis)
            affectedVisible.forEach { source ->
                queries.updateLocalReviewEventsInDeck(
                    newName = new + source.substring(old.length),
                    oldName = source,
                )
            }
            renameOptions(old, new, nowMillis)
            renamePresetAssignments(old, new, nowMillis)
            if (canonical != null) {
                overrides.forEach { (source, replacement) ->
                    if (replacement != null && replacement.isDeckOrDescendantOf(old)) {
                        queries.upsertLocalDeckOverride(
                            source,
                            new + replacement.substring(old.length),
                            nowMillis,
                        )
                    }
                }
                queries.upsertLocalDeckOverride(canonical, new, nowMillis)
                queries.upsertLocalDeckSync(
                    canonical,
                    "rename",
                    new,
                    queries.selectSyncDeckChecksum(canonical).executeAsOneOrNull() ?: "",
                    nowMillis,
                )
            } else {
                queries.selectPendingLocalDeckSync { source, operation, target, _, _, _ ->
                    Triple(source, operation, target)
                }.executeAsList()
                    .filter { (source, _, target) ->
                        source.isDeckOrDescendantOf(old) || target?.isDeckOrDescendantOf(old) == true
                    }
                    .forEach { (source, _, _) -> queries.deleteLocalDeckSync(source) }
                queries.upsertLocalDeckSync(new, "upsert", null, "", nowMillis)
            }
            queries.markBrowseIndexDirty()
        }
    }

    fun delete(name: String, nowMillis: Long) {
        val normalized = normalizeDeckName(name)
        database.transaction {
            val overrides = deckOverrides()
            val canonicalRoot = canonicalNameForDisplay(normalized, overrides)
            val affectedCanonical = downloadedDeckNames().filter { source ->
                source.remapDownloadedDeckName(overrides)
                    ?.isDeckOrDescendantOf(normalized) == true
            }
            val affectedDecks = queries.selectLocalDecks().executeAsList()
                .filter { it.isDeckOrDescendantOf(normalized) }
            require(affectedCanonical.isNotEmpty() || affectedDecks.isNotEmpty()) { "This deck no longer exists" }

            deleteLocalRows(normalized, affectedDecks)
            queries.selectLocalDeckOptions { deckName, _ -> deckName }.executeAsList()
                .filter { it.isDeckOrDescendantOf(normalized) }
                .forEach(queries::deleteLocalDeckOptions)
            queries.selectDeckPresetAssignments { deckName, _ -> deckName }.executeAsList()
                .filter { it.isDeckOrDescendantOf(normalized) }
                .forEach(queries::deleteDeckPresetAssignment)
            if (affectedCanonical.isNotEmpty()) {
                queries.selectCards { cardId, _, deckName, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
                    cardId to deckName
                }
                    .executeAsList()
                    .filter { (_, deckName) ->
                        deckName.remapDownloadedDeckName(overrides)
                            ?.isDeckOrDescendantOf(normalized) == true
                    }
                    .forEach { (cardId, _) ->
                        queries.deleteLocalSchedule(cardId)
                        queries.deleteLocalCardDueOverrideForCard(cardId)
                    }
                affectedCanonical.forEach { source ->
                    queries.upsertLocalDeckOverride(source, null, nowMillis)
                }
                queries.upsertLocalDeckSync(
                    canonicalRoot ?: affectedCanonical.first(),
                    "delete",
                    null,
                    "",
                    nowMillis,
                )
            } else {
                queries.selectPendingLocalDeckSync { source, _, _, _, _, _ -> source }
                    .executeAsList()
                    .filter { it.isDeckOrDescendantOf(normalized) }
                    .forEach(queries::deleteLocalDeckSync)
            }
            queries.markBrowseIndexDirty()
        }
    }

    fun discardSyncConflict(sourceName: String, nowMillis: Long) {
        database.transaction {
            val mutation = queries.selectLocalDeckSyncForSource(sourceName) { operation, target, _ ->
                operation to target
            }.executeAsOneOrNull()
            val target = mutation?.second
            if (mutation?.first == "rename" && target != null) {
                val affected = queries.selectLocalDecks().executeAsList()
                    .filter { it.isDeckOrDescendantOf(target) }
                renameLocalRows(target, sourceName, affected, nowMillis)
                visibleDeckNames().filter { it.isDeckOrDescendantOf(target) }.forEach { deck ->
                    queries.updateLocalReviewEventsInDeck(
                        newName = sourceName + deck.substring(target.length),
                        oldName = deck,
                    )
                }
                queries.selectLocalDeckOptions { deck, _ -> deck }.executeAsList()
                    .filter { it.isDeckOrDescendantOf(target) }
                    .forEach(queries::deleteLocalDeckOptions)
                queries.selectDeckPresetAssignments { deck, _ -> deck }.executeAsList()
                    .filter { it.isDeckOrDescendantOf(target) }
                    .forEach(queries::deleteDeckPresetAssignment)
            }
            deckOverrides().keys.filter { it.isDeckOrDescendantOf(sourceName) }
                .forEach(queries::deleteLocalDeckOverride)
            queries.deleteLocalDeckSync(sourceName)
            queries.markBrowseIndexDirty()
        }
    }

    private fun renameLocalRows(old: String, new: String, affected: List<String>, nowMillis: Long) {
        affected.forEach(queries::deleteLocalDeck)
        affected.forEach { source ->
            val target = new + source.substring(old.length)
            queries.updateLocalCardsInDeck(newName = target, oldName = source)
            deckHierarchyNames(target).forEach { queries.insertLocalDeck(it, nowMillis) }
        }
    }

    private fun renameOptions(old: String, new: String, nowMillis: Long) {
        queries.selectLocalDeckOptions { deckName, _ -> deckName }.executeAsList()
            .filter { it.isDeckOrDescendantOf(old) }
            .forEach { source ->
                queries.updateLocalDeckOptionsName(
                    newName = new + source.substring(old.length),
                    modifiedAt = nowMillis,
                    oldName = source,
                )
            }
    }

    private fun renamePresetAssignments(old: String, new: String, nowMillis: Long) {
        queries.selectDeckPresetAssignments { deckName, _ -> deckName }.executeAsList()
            .filter { it.isDeckOrDescendantOf(old) }
            .forEach { source ->
                queries.updateDeckPresetAssignmentName(
                    newName = new + source.substring(old.length),
                    modifiedAt = nowMillis,
                    oldName = source,
                )
            }
    }

    private fun deleteLocalRows(name: String, affectedDecks: List<String>) {
        val affectedCards = queries.selectLocalCards { cardId, noteGuid, deckName, _, _ ->
            Triple(cardId, noteGuid, deckName)
        }.executeAsList().filter { it.third.isDeckOrDescendantOf(name) }
        affectedCards.forEach { (cardId, _, _) ->
            queries.deleteLocalSchedule(cardId)
            queries.deleteLocalCardDueOverrideForCard(cardId)
            queries.deleteLocalReviewEventsForCard(cardId)
            queries.deleteLocalCard(cardId)
        }
        affectedCards.map { it.second }.distinct().forEach { noteGuid ->
            if (queries.countLocalCardsForNote(noteGuid).executeAsOne() == 0L) {
                queries.deleteLocalNote(noteGuid)
            }
        }
        affectedDecks.forEach(queries::deleteLocalDeck)
    }

    private fun canonicalNameForDisplay(displayName: String, overrides: Map<String, String?>): String? =
        downloadedDeckNames().firstOrNull {
            it.remapDownloadedDeckName(overrides)?.equals(displayName, ignoreCase = true) == true
        }

    private fun visibleDeckNames(): Set<String> =
        (downloadedDeckNames().mapNotNull { it.remapDownloadedDeckName(deckOverrides()) } +
            queries.selectLocalDecks().executeAsList())
            .flatMap(::deckHierarchyNames)
            .toSet()

    private fun downloadedDeckNames(): Set<String> = queries.selectDownloadedDeckNames().executeAsList()
        .flatMap(::deckHierarchyNames)
        .toSet()

    private fun deckOverrides(): Map<String, String?> =
        queries.selectLocalDeckOverrides { source, replacement -> source to replacement }
            .executeAsList()
            .toMap()
}
