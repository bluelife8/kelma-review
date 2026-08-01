package tech.kelma.app

internal data class AnkiDatabaseSnapshot(
    val collection: AnkiCollectionRow,
    val notes: List<AnkiNoteRow>,
    val cards: List<AnkiCardRow>,
    val reviews: List<AnkiReviewRow>,
    val normalizedNotetypes: List<AnkiNormalizedNotetype> = emptyList(),
    val normalizedFields: List<AnkiNormalizedField> = emptyList(),
    val normalizedTemplates: List<AnkiNormalizedTemplate> = emptyList(),
    val normalizedDecks: List<AnkiNormalizedDeck> = emptyList(),
    val normalizedDeckConfigs: List<AnkiNormalizedDeckConfig> = emptyList(),
)

internal data class AnkiCollectionRow(
    val id: Long = 1,
    val createdAtSeconds: Long,
    val modifiedAtMillis: Long,
    val schemaModifiedAtMillis: Long,
    val schemaVersion: Int = 11,
    val updateSequence: Long = -1,
    val lastSync: Long = 0,
    val configurationJson: String,
    val modelsJson: String,
    val decksJson: String,
    val deckConfigurationsJson: String,
    val tagsJson: String = "{}",
)

internal data class AnkiNoteRow(
    val id: Long,
    val guid: String,
    val notetypeId: Long,
    val modifiedAtSeconds: Long,
    val updateSequence: Long = -1,
    val tags: String,
    val fields: String,
    val sortField: String,
    val checksum: Long,
    val flags: Int = 0,
    val data: String = "",
)

internal data class AnkiCardRow(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val ordinal: Int,
    val modifiedAtSeconds: Long,
    val updateSequence: Long = -1,
    val type: Int,
    val queue: Int,
    val due: Long,
    val interval: Int,
    val factor: Int,
    val repetitions: Int,
    val lapses: Int,
    val remainingSteps: Int,
    val originalDue: Long = 0,
    val originalDeckId: Long = 0,
    val flags: Int = 0,
    val data: String = "",
)

internal data class AnkiReviewRow(
    val id: Long,
    val cardId: Long,
    val updateSequence: Long = -1,
    val ease: Int,
    val interval: Int,
    val previousInterval: Int,
    val factor: Int,
    val durationMillis: Int,
    val type: Int,
)

internal data class AnkiNormalizedNotetype(
    val id: Long,
    val name: String,
    val modifiedAtSeconds: Long,
    val updateSequence: Long,
    val configuration: ByteArray,
)

internal data class AnkiNormalizedField(
    val notetypeId: Long,
    val ordinal: Int,
    val name: String,
    val configuration: ByteArray,
)

internal data class AnkiNormalizedTemplate(
    val notetypeId: Long,
    val ordinal: Int,
    val name: String,
    val modifiedAtSeconds: Long,
    val updateSequence: Long,
    val configuration: ByteArray,
)

internal data class AnkiNormalizedDeck(
    val id: Long,
    val name: String,
    val modifiedAtSeconds: Long,
    val updateSequence: Long,
    val common: ByteArray,
    val kind: ByteArray,
)

internal data class AnkiNormalizedDeckConfig(
    val id: Long,
    val name: String,
    val modifiedAtSeconds: Long,
    val updateSequence: Long,
    val configuration: ByteArray,
)
