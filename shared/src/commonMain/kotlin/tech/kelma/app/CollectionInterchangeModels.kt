package tech.kelma.app

enum class CollectionExportFormat(val label: String, val extension: String, val mimeType: String) {
    KelmaJson("Kelma JSON (.kelma.json)", "kelma.json", "application/json"),
    AnkiDeckPackage("Anki Deck Package (.apkg)", "apkg", "application/octet-stream"),
    AnkiCollectionPackage("Anki Collection Package (.colpkg)", "colpkg", "application/octet-stream"),
    NotesText("Notes in Plain Text (.txt)", "txt", "text/tab-separated-values"),
    CardsText("Cards in Plain Text (.txt)", "txt", "text/tab-separated-values"),
}

data class CollectionExportOptions(
    val format: CollectionExportFormat,
    val deckName: String?,
    val includeScheduling: Boolean = true,
    val includeDeckPresets: Boolean = true,
    val includeMedia: Boolean = true,
    val supportOlderAnkiVersions: Boolean = false,
)

data class CollectionExportFile(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class ImmutableReviewExport(
    val reviewId: Long,
    val noteGuid: String,
    val cardOrdinal: Int,
    val rating: Rating,
    val durationMillis: Long,
)

enum class TextImportKind(val label: String) {
    Notes("Notes in Plain Text"),
    Cards("Cards in Plain Text"),
}

data class CollectionImportPlan(
    val sourceName: String,
    val decks: Set<String>,
    val notetypes: List<ImportedNotetype>,
    val notes: List<ImportedNote>,
    val cards: List<ImportedCard>,
    val reviews: List<ImportedReview>,
    val media: List<ImportedMedia>,
    val deckOptions: Map<String, ImportedDeckOptions> = emptyMap(),
    val presets: List<ImportedDeckOptions> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class ImportedNotetype(
    val sourceId: Long,
    val name: String,
    val definitionJson: String,
)

data class ImportedNote(
    val sourceId: Long,
    val guid: String,
    val notetypeId: Long,
    val fields: List<String>,
    val tags: List<String>,
)

data class ImportedCard(
    val sourceId: Long,
    val noteSourceId: Long,
    val deckName: String,
    val ordinal: Int,
    val createdAtMillis: Long? = null,
    val createdOnImport: Boolean = false,
)

data class ImportedReview(
    val reviewId: Long,
    val cardSourceId: Long,
    val rating: Rating,
    val durationMillis: Long,
)

data class ImportedMedia(
    val filename: String,
    val bytes: ByteArray,
)

data class ImportedDeckOptions(
    val sourceId: Long,
    val name: String,
    val options: DeckOptions,
    val preferredId: String? = null,
)

internal data class ImportedCollectionState(
    val report: CollectionImportResult,
    val content: LocalContentSnapshot,
    val reviews: LocalReviewSnapshot,
    val optimizer: SchedulerOptimizerState,
)

data class CollectionImportResult(
    val addedNotes: Int,
    val reusedNotes: Int,
    val copiedConflicts: Int,
    val addedCards: Int,
    val addedReviews: Int,
    val skippedReviewConflicts: Int,
    val addedMedia: Int,
    val renamedMedia: Int,
    val warnings: List<String>,
) {
    val message: String
        get() = buildString {
            append("Imported $addedNotes notes, $addedCards cards")
            if (addedReviews > 0) append(", $addedReviews reviews")
            if (addedMedia > 0) append(", $addedMedia media files")
            if (renamedMedia > 0) append(" · $renamedMedia media name collisions resolved")
            if (reusedNotes > 0) append(" · $reusedNotes existing notes matched")
            if (copiedConflicts > 0) append(" · $copiedConflicts GUID conflicts kept as copies")
            if (skippedReviewConflicts > 0) append(" · $skippedReviewConflicts review conflicts skipped")
        }
}
