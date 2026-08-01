package tech.kelma.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class SchedulerOptimizerPersistenceTest {
    @Test
    fun candidateSurvivesRestartWithoutChangingActiveProfile() {
        withOptimizerStore("candidate-restart") { databaseFile, driver, store ->
            store.saveSignedInState(OptimizerAuth, optimizerCollection(), OptimizerNow)
            val schedulesBefore = store.load(OptimizerNow).localReviews.schedules
            val prepared = store.prepareSchedulerOptimization("UTC", nowMillis = OptimizerNow + 1)
            val jobId = assertNotNull(prepared.job).jobId
            assertEquals(SchedulerOptimizerJobStatus.Running, prepared.job.status)
            val completed = store.runSchedulerOptimization(jobId)
            val candidate = assertNotNull(completed.pendingCandidate)
            assertEquals(SchedulerOptimizerJobStatus.Completed, completed.job?.status)
            assertEquals(21, candidate.payload.parameters.size)
            assertEquals(0, store.loadSchedulerProfile().local.version)
            assertEquals(DefaultFsrs6Parameters, store.loadSchedulerProfile().local.settings.parameters)
            assertEquals(schedulesBefore, store.load(OptimizerNow).localReviews.schedules)

            driver.close()
            val reopenedDriver = openDesktopDatabase(databaseFile)
            try {
                val reopened = PersistentCollectionStore(KelmaDatabase(reopenedDriver))
                val restored = reopened.loadSchedulerOptimizer(recoverInterrupted = true)
                assertEquals(candidate, restored.pendingCandidate)
                assertEquals(0, reopened.loadSchedulerProfile().local.version)
            } finally {
                reopenedDriver.close()
            }
        }
    }

    @Test
    fun applyIsExplicitTransactionalAndPreservesRetentionOwnership() {
        withOptimizerStore("apply") { _, _, store ->
            store.saveSignedInState(OptimizerAuth, optimizerCollection(), OptimizerNow)
            store.applyAccountSchedulerProfile(
                SchedulerProfileSettings(
                    desiredRetention = 0.94,
                    retentionSource = SchedulerProfileSource.Manual,
                ),
                publishToCloud = false,
                nowMillis = OptimizerNow + 1,
            )
            val candidate = optimize(store, OptimizerNow + 2)
            val before = store.loadSchedulerProfile().local
            assertNotEquals(candidate.payload.parameters, before.settings.parameters)

            val applied = store.applySchedulerOptimizerCandidate(
                candidate.candidateId,
                publishToCloud = false,
                nowMillis = OptimizerNow + 3,
            )
            assertEquals(SchedulerOptimizerCandidateStatus.Applied, applied.first.candidate?.status)
            assertEquals(candidate.payload.parameters, applied.second.local.settings.parameters)
            assertEquals(SchedulerProfileSource.ClientOptimized, applied.second.local.settings.parameterSource)
            assertEquals(0.94, applied.second.local.settings.desiredRetention)
            assertEquals(SchedulerProfileSource.Manual, applied.second.local.settings.retentionSource)
            assertEquals("kelma-fsrs-v6", applied.second.local.settings.optimizer)
            assertEquals(candidate.payload.optimizerVersion, applied.second.local.settings.optimizerVersion)
            assertTrue(applied.second.local.settings.qualityMetrics.isNotEmpty())
            assertTrue(store.prepareSyncUpload().isEmpty)
        }
    }

    @Test
    fun publishUsesExistingConflictSafeProfileOutbox() {
        withOptimizerStore("publish") { _, _, store ->
            store.saveSignedInState(OptimizerAuth, optimizerCollection(), OptimizerNow)
            store.observeCloudSchedulerProfile(
                SchedulerProfileResponse(profile = CloudSchedulerProfile(version = 0)),
                OptimizerNow,
            )
            val candidate = optimize(store, OptimizerNow + 1)
            store.applySchedulerOptimizerCandidate(
                candidate.candidateId,
                publishToCloud = true,
                nowMillis = OptimizerNow + 2,
            )
            val upload = assertNotNull(store.prepareSyncUpload().schedulerProfile)
            assertEquals(SchedulerProfileSource.ClientOptimized, upload.parameterSource)
            assertEquals(candidate.payload.parameters, upload.parameters)
            assertEquals(64, upload.optimizationHistoryHash.length)
            assertEquals(candidate.payload.throughReviewId, upload.optimizationThroughReviewId)
            assertTrue(upload.optimizer.isNotBlank())
            assertTrue(upload.optimizerVersion.isNotBlank())
        }
    }

    @Test
    fun cancelDiscardStaleAndInterruptedJobsNeverApply() {
        withOptimizerStore("lifecycle") { databaseFile, driver, store ->
            store.saveSignedInState(OptimizerAuth, optimizerCollection(), OptimizerNow)
            val cancelledJob = assertNotNull(
                store.prepareSchedulerOptimization("UTC", nowMillis = OptimizerNow + 1).job,
            )
            store.cancelSchedulerOptimization(cancelledJob.jobId, OptimizerNow + 2)
            val cancelled = store.runSchedulerOptimization(cancelledJob.jobId)
            assertEquals(SchedulerOptimizerJobStatus.Cancelled, cancelled.job?.status)
            assertNull(cancelled.pendingCandidate)
            assertEquals(0, store.loadSchedulerProfile().local.version)

            val backgroundedJob = assertNotNull(
                store.prepareSchedulerOptimization("UTC", nowMillis = OptimizerNow + 2).job,
            )
            val interrupted = store.interruptSchedulerOptimization(backgroundedJob.jobId, OptimizerNow + 3)
            assertEquals(SchedulerOptimizerJobStatus.Interrupted, interrupted.job?.status)
            assertEquals("app_backgrounded", interrupted.job?.reasonCode)
            assertNull(interrupted.pendingCandidate)
            assertEquals(0, store.loadSchedulerProfile().local.version)

            val discard = optimize(store, OptimizerNow + 4)
            val discarded = store.discardSchedulerOptimizerCandidate(discard.candidateId, OptimizerNow + 5)
            assertEquals(SchedulerOptimizerCandidateStatus.Discarded, discarded.candidate?.status)
            assertEquals(0, store.loadSchedulerProfile().local.version)

            val stale = optimize(store, OptimizerNow + 5)
            val card = optimizerCollection().cards.getValue(1)
            store.recordReview(card, Rating.Good, OptimizerNow + 10, 1_000)
            assertEquals(
                SchedulerOptimizerCandidateStatus.Stale,
                store.loadSchedulerOptimizer().candidate?.status,
            )
            assertFailsWith<IllegalArgumentException> {
                store.applySchedulerOptimizerCandidate(stale.candidateId, false, OptimizerNow + 11)
            }

            store.prepareSchedulerOptimization("UTC", nowMillis = OptimizerNow + 12)
            driver.close()
            val reopenedDriver = openDesktopDatabase(databaseFile)
            try {
                val reopened = PersistentCollectionStore(KelmaDatabase(reopenedDriver))
                val recovered = reopened.loadSchedulerOptimizer(recoverInterrupted = true)
                assertEquals(SchedulerOptimizerJobStatus.Interrupted, recovered.job?.status)
                assertNull(recovered.pendingCandidate)
                assertEquals(0, reopened.loadSchedulerProfile().local.version)
            } finally {
                reopenedDriver.close()
            }
        }
    }

    @Test
    fun accountChangeClearsOptimizerStateAndChecksumMatchesServerContract() {
        withOptimizerStore("account") { _, _, store ->
            store.saveSignedInState(OptimizerAuth, optimizerCollection(), OptimizerNow)
            optimize(store, OptimizerNow + 1)
            store.saveSignedInState(
                OptimizerAuth.copy(username = "other@example.com"),
                SyncedCollection(),
                OptimizerNow + 2,
            )
            assertEquals(SchedulerOptimizerState(), store.loadSchedulerOptimizer())
        }
        assertEquals(
            "46a3a58da871440db7ede339f4ccaf508f5283d5060e20a705c1c3b889d7a17a",
            schedulerReviewChecksum("g1", 0, 3, 10, 1, 2500, 4000, 1),
        )
    }
}

private fun optimize(
    store: PersistentCollectionStore,
    nowMillis: Long,
): SchedulerOptimizerCandidate {
    val job = assertNotNull(store.prepareSchedulerOptimization("UTC", nowMillis = nowMillis).job)
    val completed = store.runSchedulerOptimization(job.jobId)
    assertEquals(SchedulerOptimizerJobStatus.Completed, completed.job?.status, completed.job?.reasonCode)
    return assertNotNull(completed.pendingCandidate)
}

private inline fun withOptimizerStore(
    name: String,
    block: (java.io.File, app.cash.sqldelight.db.SqlDriver, PersistentCollectionStore) -> Unit,
) {
    val directory = Files.createTempDirectory("kelma-optimizer-$name").toFile()
    val databaseFile = directory.resolve("kelma.db")
    val driver = openDesktopDatabase(databaseFile)
    try {
        block(databaseFile, driver, PersistentCollectionStore(KelmaDatabase(driver)))
    } finally {
        runCatching { driver.close() }
        directory.deleteRecursively()
    }
}

private fun optimizerCollection(): SyncedCollection {
    val cards = (0 until 120).associate { cardIndex ->
        val cardId = (cardIndex + 1).toLong()
        cardId to SyncCard(cardId, "optimizer-note-$cardIndex", "Deck", 0)
    }
    val intervals = listOf(0, 1, 2, 4, 7, 14, 3, 21, 5, 30, 10, 45, 8)
    val reviews = buildList {
        cards.values.forEachIndexed { cardIndex, card ->
            var reviewedAt = OptimizerNow - 300 * MillisPerDay + cardIndex * 1_000L
            repeat(14) { reviewIndex ->
                if (reviewIndex > 0) {
                    val days = intervals[reviewIndex - 1]
                    reviewedAt += if (days == 0) 10 * 60_000L else days * MillisPerDay
                }
                val rating = if (reviewIndex == 0) {
                    cardIndex % 4 + 1
                } else {
                    when (val selector = (cardIndex * 17 + reviewIndex * 7) % 20) {
                        in 0..3 -> 1
                        in 4..7 -> 2
                        in 8..16 -> 3
                        else -> 4
                    }
                }
                add(
                    SyncReview(
                        reviewId = reviewedAt,
                        sourceCardId = card.cardId,
                        noteGuid = card.noteGuid,
                        cardOrd = card.ord,
                        deckName = card.deckName,
                        ease = rating,
                        takenMillis = 2_500,
                        reviewKind = if (reviewIndex < 2) 0 else 1,
                        checksum = "optimizer-checksum-$reviewedAt",
                    ),
                )
            }
        }
    }.associateBy(SyncReview::reviewId)
    return SyncedCollection(cards = cards, reviews = reviews, deckNames = setOf("Deck"))
}

private val OptimizerAuth = StoredSyncAuth(
    token = "optimizer-token",
    clientId = "optimizer-client",
    endpoint = "https://acceptance.kelma.invalid",
    username = "optimizer@example.com",
)
private const val OptimizerNow = 1_900_000_000_000L
