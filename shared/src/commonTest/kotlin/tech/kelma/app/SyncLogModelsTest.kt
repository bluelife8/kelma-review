package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncLogModelsTest {
    @Test
    fun outboxSummaryAggregatesNotesAndCardsWithoutListingIdentifiers() {
        val body = NotePushBody(1, listOf("front", "back"), emptyList(), "now", "base")
        val card = CardPushBody("private-guid", "Deck", 0, kotlinx.serialization.json.JsonObject(emptyMap()), "now")
        val plan = SyncUploadPlan(
            notes = (1..1_200).map { index ->
                PendingNoteUpload(
                    guid = "private-guid-$index",
                    operation = "upsert",
                    body = body,
                    notetype = null,
                    deck = null,
                    cards = listOf(index.toLong() to card),
                    forceOverride = false,
                )
            },
        )

        val lines = plan.summaryLines()

        assertEquals(1, lines.size)
        assertTrue(lines.single().message.contains("1,200 notes"))
        assertTrue(lines.single().message.contains("1,200 cards"))
        assertFalse(lines.single().message.contains("private-guid"))
    }

    @Test
    fun cardTransferProgressUsesOneReplaceableAggregateLine() {
        val started = SyncPushProgress(SyncPushResource.Cards, 0, 1_200).toSyncProgress(false)
        val advanced = SyncPushProgress(SyncPushResource.Cards, 500, 1_200).toSyncProgress(true)

        assertEquals("Uploading cards · 0 / 1,200", started.message)
        assertFalse(started.replaceLatest)
        assertEquals("Uploading cards · 500 / 1,200", advanced.message)
        assertTrue(advanced.replaceLatest)
    }

    @Test
    fun mediaProgressShowsTransferredAndTotalGibibytes() {
        val progress = SyncPullProgress(
            resource = SyncPullResource.Media,
            completed = 512,
            total = 2_000,
            completedBytes = 1_073_741_824L,
            totalBytes = 4_294_967_296L,
            detail = "KelmaSync TAR pipeline · 5/8 prepared · 2/8 downloaded · sequential",
        ).toSyncProgress(replaceLatest = true)

        assertEquals(
            "Downloading media files · 1.00 GiB / 4.00 GiB · 512 / 2,000 files · " +
                "KelmaSync TAR pipeline · 5/8 prepared · 2/8 downloaded · sequential",
            progress.message,
        )
        assertTrue(progress.replaceLatest)
    }

    @Test
    fun destructiveDeckPlanListsNamesAndDeletionCounts() {
        val plan = SyncUploadPlan(
            decks = listOf(
                PendingDeckUpload(
                    sourceName = "german34",
                    operation = "delete",
                    targetName = null,
                    targetBody = null,
                    cards = emptyList(),
                    deleteRequest = BatchDeleteRequest(
                        notes = listOf("one", "two"),
                        cards = listOf(1, 2),
                        decks = listOf("german34"),
                    ),
                    forceOverride = false,
                ),
            ),
        )

        val lines = plan.summaryLines()

        assertEquals("OUTBOX", lines.first().phase)
        assertTrue(lines.last().message.contains("delete: german34"))
        assertTrue(lines.last().message.contains("delete 2 cards/2 notes"))
    }
}
