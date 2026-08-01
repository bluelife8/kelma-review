package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.kelma.db.KelmaQueries

internal fun loadLocalContentSnapshot(
    queries: KelmaQueries,
    json: Json,
): LocalContentSnapshot {
    val stringList = ListSerializer(String.serializer())
    val notes = queries.selectLocalNotes { guid, notetypeId, fields, tags, _ ->
        guid to SyncNote(
            guid = guid,
            notetypeId = notetypeId,
            fields = json.decodeFromString(stringList, fields),
            tags = json.decodeFromString(stringList, tags),
        )
    }.executeAsList().toMap()
    val notetypes = queries.selectLocalNotetypes { id, name, definition, _ ->
        id to SyncNotetype(
            notetypeId = id,
            name = name,
            definition = json.parseToJsonElement(definition) as? JsonObject ?: JsonObject(emptyMap()),
        )
    }.executeAsList().toMap()
    val cards = queries.selectLocalCards { cardId, noteGuid, deckName, ord, _ ->
        cardId to SyncCard(
            cardId = cardId,
            noteGuid = noteGuid,
            deckName = deckName,
            ord = ord.toInt(),
            scheduling = JsonObject(emptyMap()),
        )
    }.executeAsList().toMap()
    val media = queries.selectLocalMedia { filename, mimeType, checksum, bytes, modifiedAt, state ->
        filename to LocalMediaAttachment(filename, mimeType, checksum, bytes, modifiedAt, state)
    }.executeAsList().toMap()
    val overrides = queries.selectLocalNoteOverrides { guid, fields, tags ->
        guid to LocalNoteOverride(
            fields = json.decodeFromString(stringList, fields),
            tags = json.decodeFromString(stringList, tags),
        )
    }.executeAsList().toMap()
    val storedDeckOptions = queries.selectLocalDeckOptions { deckName, optionsJson ->
        deckName to json.decodeFromString<DeckOptions>(optionsJson).validated()
    }.executeAsList().toMap()
    val presets = queries.selectDeckOptionPresets { id, name, optionsJson, createdAt, modifiedAt ->
        DeckOptionsPreset(
            id = id,
            name = name,
            options = json.decodeFromString<DeckOptions>(optionsJson).validated(),
            createdAtMillis = createdAt,
            modifiedAtMillis = modifiedAt,
        )
    }.executeAsList()
    val assignments = queries.selectDeckPresetAssignments { deckName, presetId ->
        deckName to presetId
    }.executeAsList().toMap()
    val presetsById = presets.associateBy(DeckOptionsPreset::id)
    val effectiveDeckOptions = storedDeckOptions.toMutableMap().apply {
        assignments.forEach { (deckName, presetId) ->
            presetsById[presetId]?.let { put(deckName, it.options) }
        }
    }
    val deckOverrides = queries.selectLocalDeckOverrides { source, replacement ->
        source to replacement
    }.executeAsList().toMap()
    val addedByDeck = cards.values.groupBy(SyncCard::deckName)
        .mapValues { (_, deckCards) -> deckCards.mapTo(mutableSetOf(), SyncCard::cardId) }
    val changedByDeck = mutableMapOf<String, MutableSet<Long>>()
    val changedNoteGuids = queries.selectAllLocalNoteSyncGuids().executeAsList().toSet() - notes.keys
    if (changedNoteGuids.isNotEmpty() || deckOverrides.isNotEmpty()) {
        queries.selectCards { cardId, noteGuid, deckName, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            Triple(cardId, noteGuid, deckName)
        }.executeAsList().forEach { (cardId, noteGuid, deckName) ->
            val visibleDeck = deckName.remapDownloadedDeckName(deckOverrides)
            val deckWasMoved = visibleDeck != null && !visibleDeck.equals(deckName, ignoreCase = true)
            if (visibleDeck != null && (noteGuid in changedNoteGuids || deckWasMoved)) {
                changedByDeck.getOrPut(visibleDeck, ::mutableSetOf).add(cardId)
            }
        }
    }
    val pendingCardMetadataIds = (
        queries.selectLocalCardStudyStates { _, _, cardId, _, _, _ -> cardId }.executeAsList() +
            queries.selectLocalCardResets { _, _, cardId, _, _, _ -> cardId }.executeAsList() +
            queries.selectLocalCardDueOverrides { _, _, cardId, _, _, _ -> cardId }.executeAsList()
        ).distinct()
    if (pendingCardMetadataIds.isNotEmpty()) {
        val syncedDeckByCard = queries.selectCards { cardId, _, deckName, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            cardId to deckName
        }.executeAsList().toMap()
        pendingCardMetadataIds.forEach { cardId ->
            val deckName = cards[cardId]?.deckName ?: syncedDeckByCard[cardId]
            val visibleDeck = deckName?.remapDownloadedDeckName(deckOverrides)
            if (visibleDeck != null) changedByDeck.getOrPut(visibleDeck, ::mutableSetOf).add(cardId)
        }
    }
    val pendingDeckNames = addedByDeck.keys + changedByDeck.keys
    val pendingSyncByDeck = pendingDeckNames.associateWith { deckName ->
        PendingDeckChanges(
            addedCardIds = addedByDeck[deckName].orEmpty(),
            changedCardIds = changedByDeck[deckName].orEmpty(),
        )
    }
    return LocalContentSnapshot(
        notes = notes,
        cards = cards,
        notetypes = notetypes,
        media = media,
        overrides = overrides,
        deckNames = queries.selectLocalDecks().executeAsList().flatMap(::deckHierarchyNames).toSet(),
        deckOptions = effectiveDeckOptions,
        deckPresets = DeckPresetState(presets, assignments),
        deckOverrides = deckOverrides,
        cardFlags = queries.selectLocalCardFlags { cardId, flag -> cardId to flag.toInt() }
            .executeAsList().toMap(),
        cardStudyStates = queries.selectLocalCardStudyStates {
                noteGuid, cardOrd, _, studyState, _, _ ->
            cardStudyKey(noteGuid, cardOrd.toInt()) to studyState.asCardStudyState()
        }.executeAsList().toMap(),
        deletedNoteGuids = queries.selectDeletedLocalNoteSyncGuids().executeAsList().toSet(),
        pendingSyncByDeck = pendingSyncByDeck,
    )
}
