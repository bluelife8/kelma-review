package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

internal class DeckPresetPersistence(
    private val database: KelmaDatabase,
    private val json: Json,
    private val rebuildSchedules: () -> Unit,
) {
    private val queries = database.kelmaQueries

    fun saveDeckOptions(deckName: String, options: DeckOptions, nowMillis: Long) {
        database.transaction {
            val presetId = queries.selectDeckPresetAssignment(deckName).executeAsOneOrNull()
            val affectedDecks = if (presetId == null) {
                queries.upsertLocalDeckOptions(deckName, json.encodeToString(options), nowMillis)
                listOf(deckName)
            } else {
                queries.updateDeckOptionPreset(json.encodeToString(options), nowMillis, presetId)
                queries.selectDeckPresetAssignmentsForPreset(presetId).executeAsList()
            }
            affectedDecks.forEach { queueDailyLimitSync(it, nowMillis) }
            rebuildSchedules()
        }
    }

    fun create(
        deckName: String,
        requestedName: String,
        options: DeckOptions,
        nowMillis: Long,
    ): LocalContentSnapshot {
        val name = validatePresetName(requestedName)
        require(nameAvailable(name)) { "A preset with this name already exists" }
        database.transaction {
            val id = randomUuidString()
            queries.insertDeckOptionPreset(id, name, json.encodeToString(options.validated()), nowMillis, nowMillis)
            queries.upsertDeckPresetAssignment(deckName, id, nowMillis)
            queueDailyLimitSync(deckName, nowMillis)
            rebuildSchedules()
        }
        return loadLocalContentSnapshot(queries, json)
    }

    fun clone(
        deckName: String,
        sourcePresetId: String,
        requestedName: String,
        nowMillis: Long,
    ): LocalContentSnapshot {
        val source = requireNotNull(load(sourcePresetId)) { "Preset no longer exists" }
        return create(deckName, requestedName, source.options, nowMillis)
    }

    fun assign(deckName: String, presetId: String?, nowMillis: Long): LocalContentSnapshot {
        database.transaction {
            if (presetId == null) {
                queries.deleteDeckPresetAssignment(deckName)
                if (queries.selectLocalDeckOptionsForDeck(deckName).executeAsOneOrNull() != null) {
                    queueDailyLimitSync(deckName, nowMillis)
                }
            } else {
                require(load(presetId) != null) { "Preset no longer exists" }
                queries.upsertDeckPresetAssignment(deckName, presetId, nowMillis)
                queueDailyLimitSync(deckName, nowMillis)
            }
            rebuildSchedules()
        }
        return loadLocalContentSnapshot(queries, json)
    }

    fun rename(presetId: String, requestedName: String, nowMillis: Long): LocalContentSnapshot {
        val name = validatePresetName(requestedName)
        val current = requireNotNull(load(presetId)) { "Preset no longer exists" }
        require(current.name.equals(name, ignoreCase = true) || nameAvailable(name)) {
            "A preset with this name already exists"
        }
        queries.renameDeckOptionPreset(name, nowMillis, presetId)
        return loadLocalContentSnapshot(queries, json)
    }

    fun delete(presetId: String, nowMillis: Long): LocalContentSnapshot {
        val preset = requireNotNull(load(presetId)) { "Preset no longer exists" }
        database.transaction {
            queries.selectDeckPresetAssignmentsForPreset(presetId).executeAsList().forEach { deckName ->
                queries.upsertLocalDeckOptions(deckName, json.encodeToString(preset.options), nowMillis)
                queries.deleteDeckPresetAssignment(deckName)
            }
            queries.deleteDeckOptionPreset(presetId)
            rebuildSchedules()
        }
        return loadLocalContentSnapshot(queries, json)
    }

    fun clear() {
        queries.clearDeckPresetAssignments()
        queries.clearDeckOptionPresets()
    }

    private fun load(presetId: String): DeckOptionsPreset? = queries.selectDeckOptionPreset(presetId) {
            id, name, optionsJson, createdAt, modifiedAt ->
        DeckOptionsPreset(
            id,
            name,
            json.decodeFromString<DeckOptions>(optionsJson).validated(),
            createdAt,
            modifiedAt,
        )
    }.executeAsOneOrNull()

    private fun nameAvailable(name: String): Boolean = queries.selectDeckOptionPresets {
            _, existingName, _, _, _ -> existingName
    }.executeAsList().none { it.equals(name, ignoreCase = true) }

    private fun queueDailyLimitSync(deckName: String, nowMillis: Long) {
        val existing = queries.selectLocalDeckSyncMutations { source, operation, target, baseChecksum ->
            DeckSyncMutation(source, operation, target, baseChecksum)
        }.executeAsList().firstOrNull { mutation ->
            mutation.sourceName.equals(deckName, ignoreCase = true) ||
                mutation.targetName?.equals(deckName, ignoreCase = true) == true ||
                (mutation.operation == "rename" && mutation.targetName != null &&
                    deckName.isDeckOrDescendantOf(mutation.targetName))
        }
        if (existing?.operation == "delete") return
        queries.upsertLocalDeckSync(
            sourceName = existing?.sourceName ?: deckName,
            operation = existing?.operation ?: "upsert",
            targetName = existing?.targetName,
            baseChecksum = existing?.baseChecksum
                ?: queries.selectSyncDeckChecksum(deckName).executeAsOneOrNull().orEmpty(),
            modifiedAt = nowMillis,
        )
    }
}

private data class DeckSyncMutation(
    val sourceName: String,
    val operation: String,
    val targetName: String?,
    val baseChecksum: String,
)
