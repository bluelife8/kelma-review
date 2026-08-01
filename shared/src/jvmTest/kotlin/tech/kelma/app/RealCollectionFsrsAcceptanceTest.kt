package tech.kelma.app

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase
import tech.kelma.fsrs.Card as FsrsCard
import tech.kelma.fsrs.Rating as FsrsRating
import tech.kelma.fsrs.Scheduler as FsrsSchedulerV6
import tech.kelma.fsrs.State as FsrsState

/**
 * Opt-in acceptance against a real Anki collection.
 *
 * The source database is opened read-only and only card identity plus revlog
 * timing/rating metadata is selected. Note fields, tags, deck names, media,
 * credentials, and source scheduling projections are never read.
 */
class RealCollectionFsrsAcceptanceTest {
    @Test
    fun realReviewHistoryReplaysThroughPersistentClientProjection() {
        val sourcePath = System.getenv(CollectionEnvironment)
        if (sourcePath.isNullOrBlank()) {
            check(System.getenv(RequiredEnvironment) != "1") {
                "$CollectionEnvironment is required for real-collection acceptance"
            }
            return
        }

        val source = File(sourcePath).canonicalFile
        require(source.isFile) { "Real-collection acceptance source does not exist" }
        val sourceSize = source.length()
        val sourceModifiedAt = source.lastModified()
        val startedAt = System.nanoTime()
        lateinit var dataset: RealReviewDataset
        lateinit var expected: Map<Long, LocalCardSchedule>
        lateinit var persisted: LocalReviewSnapshot
        lateinit var reopened: LocalReviewSnapshot

        val replayMillis = measureTimeMillis {
            dataset = readDataset(source)
            expected = replayDirectly(dataset)
        }
        val persistenceMillis = measureTimeMillis {
            val directory = kotlin.io.path.createTempDirectory("kelma-real-fsrs-").toFile()
            val databaseFile = directory.resolve("kelma.db")
            try {
                var driver = openDesktopDatabase(databaseFile)
                var store = PersistentCollectionStore(KelmaDatabase(driver))
                persisted = store.saveSignedInState(
                    auth = StoredSyncAuth(
                        token = "real-collection-acceptance",
                        clientId = "local-read-only",
                        endpoint = "https://acceptance.kelma.invalid",
                        username = "local-acceptance",
                    ),
                    collection = dataset.asCollectionWithReverseReviewInsertion(),
                    nowMillis = dataset.latestReviewAtMillis,
                )
                assertSchedules(expected, persisted.schedules)

                driver.close()
                driver = openDesktopDatabase(databaseFile)
                store = PersistentCollectionStore(KelmaDatabase(driver))
                reopened = store.load(dataset.latestReviewAtMillis).localReviews
                assertSchedules(expected, reopened.schedules)
                driver.close()
            } finally {
                directory.deleteRecursively()
            }
        }

        assertEquals(sourceSize, source.length(), "Acceptance must not resize the source database")
        assertEquals(
            sourceModifiedAt,
            source.lastModified(),
            "Acceptance must not modify the source database",
        )
        assertEquals(persisted.schedules, reopened.schedules, "Restart changed projected schedules")
        writeGoFixture(dataset, expected)
        writeReport(
            RealAcceptanceReport(
                sourceFingerprint = dataset.fingerprint,
                activeCards = dataset.cards.size,
                totalReviewRows = dataset.totalReviewRows,
                qualifyingReviewRows = dataset.qualifyingReviewRows,
                activeQualifyingReviews = dataset.reviews.size,
                reviewedActiveCards = expected.size,
                projectedSchedules = reopened.schedules.size,
                excludedNonRatingRows = dataset.totalReviewRows - dataset.qualifyingReviewRows,
                excludedDeletedCardRows = dataset.qualifyingReviewRows - dataset.reviews.size,
                replayMillis = replayMillis,
                persistenceMillis = persistenceMillis,
                totalMillis = (System.nanoTime() - startedAt) / 1_000_000,
            ),
        )
    }
}

private data class RealReviewDataset(
    val cards: LinkedHashMap<Long, SyncCard>,
    val reviews: List<SyncReview>,
    val totalReviewRows: Long,
    val qualifyingReviewRows: Long,
    val latestReviewAtMillis: Long,
    val fingerprint: String,
) {
    fun asCollectionWithReverseReviewInsertion(): SyncedCollection {
        val reversedReviews = linkedMapOf<Long, SyncReview>()
        reviews.asReversed().forEach { reversedReviews[it.reviewId] = it }
        return SyncedCollection(
            cards = cards,
            reviews = reversedReviews,
            deckNames = setOf(AcceptanceDeckName),
            serverTime = "real-collection-acceptance",
        )
    }
}

private data class RealAcceptanceReport(
    val sourceFingerprint: String,
    val activeCards: Int,
    val totalReviewRows: Long,
    val qualifyingReviewRows: Long,
    val activeQualifyingReviews: Int,
    val reviewedActiveCards: Int,
    val projectedSchedules: Int,
    val excludedNonRatingRows: Long,
    val excludedDeletedCardRows: Long,
    val replayMillis: Long,
    val persistenceMillis: Long,
    val totalMillis: Long,
)

@Serializable
private data class GoFixtureHeader(
    val schemaVersion: Int,
    val sourceFingerprint: String,
    val caseCount: Int,
    val reviewCount: Int,
)

@Serializable
private data class GoFixtureCase(
    val cardId: Long,
    val reviews: List<GoFixtureReview>,
    val expected: LocalCardSchedule,
)

@Serializable
private data class GoFixtureReview(val reviewId: Long, val rating: Int)

private fun readDataset(source: File): RealReviewDataset {
    Class.forName("org.sqlite.JDBC")
    val digest = MessageDigest.getInstance("SHA-256")
    val cards = linkedMapOf<Long, SyncCard>()
    val reviews = mutableListOf<SyncReview>()
    var totalReviewRows: Long
    var qualifyingReviewRows: Long

    DriverManager.getConnection("jdbc:sqlite:file:${source.absolutePath}?mode=ro").use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { it.execute("PRAGMA query_only = ON") }
        totalReviewRows = connection.singleLong("SELECT COUNT(*) FROM revlog")
        qualifyingReviewRows = connection.singleLong(
            "SELECT COUNT(*) FROM revlog WHERE ease BETWEEN 1 AND 4",
        )
        connection.prepareStatement("SELECT id, nid, ord FROM cards ORDER BY id").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val cardId = rows.getLong(1)
                    val noteId = rows.getLong(2)
                    val ordinal = rows.getInt(3)
                    digest.updateLong(cardId)
                    digest.updateLong(noteId)
                    digest.updateInt(ordinal)
                    cards[cardId] = SyncCard(
                        cardId = cardId,
                        noteGuid = acceptanceNoteGuid(noteId),
                        deckName = AcceptanceDeckName,
                        ord = ordinal,
                    )
                }
            }
        }
        connection.prepareStatement(
            """
            SELECT r.id, r.cid, r.ease, r.type
            FROM revlog r
            INNER JOIN cards c ON c.id = r.cid
            WHERE r.ease BETWEEN 1 AND 4
            ORDER BY r.id
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val reviewId = rows.getLong(1)
                    val cardId = rows.getLong(2)
                    val ease = rows.getInt(3)
                    val kind = rows.getInt(4)
                    val card = checkNotNull(cards[cardId])
                    digest.updateLong(reviewId)
                    digest.updateLong(cardId)
                    digest.updateInt(ease)
                    digest.updateInt(kind)
                    reviews += SyncReview(
                        reviewId = reviewId,
                        sourceCardId = cardId,
                        noteGuid = card.noteGuid,
                        cardOrd = card.ord,
                        deckName = AcceptanceDeckName,
                        ease = ease,
                        reviewKind = kind,
                    )
                }
            }
        }
        connection.rollback()
    }

    require(cards.isNotEmpty()) { "Acceptance source contains no active cards" }
    require(reviews.isNotEmpty()) { "Acceptance source contains no qualifying active reviews" }
    return RealReviewDataset(
        cards = cards,
        reviews = reviews,
        totalReviewRows = totalReviewRows,
        qualifyingReviewRows = qualifyingReviewRows,
        latestReviewAtMillis = reviews.maxOf(SyncReview::reviewId),
        fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
}

private fun replayDirectly(dataset: RealReviewDataset): Map<Long, LocalCardSchedule> {
    val scheduler = FsrsSchedulerV6(tech.kelma.fsrs.SchedulerConfig.default())
    return dataset.reviews.groupBy(SyncReview::sourceCardId).mapValues { (cardId, reviews) ->
        var current = FsrsCard.new(cardId, reviews.first().reviewId)
        var lapses = 0
        reviews.forEach { review ->
            val rating = FsrsRating.fromValue(review.ease)
            if (current.state == FsrsState.Review && rating == FsrsRating.Again) lapses++
            current = scheduler.review(current, rating, review.reviewId)
        }
        val lastReviewAt = checkNotNull(current.lastReviewAtMillis)
        LocalCardSchedule(
            cardId = cardId,
            phase = when (current.state) {
                FsrsState.Learning -> ReviewPhase.Learning
                FsrsState.Review -> ReviewPhase.Review
                FsrsState.Relearning -> ReviewPhase.Relearning
            },
            dueAtMillis = current.dueAtMillis,
            stability = checkNotNull(current.stability),
            difficulty = checkNotNull(current.difficulty),
            scheduledDays = FsrsSchedulerV6.elapsedDays(lastReviewAt, current.dueAtMillis)
                .coerceIn(0, scheduler.config.maximumIntervalDays),
            repetitions = reviews.size,
            lapses = lapses,
            lastReviewAtMillis = lastReviewAt,
            step = current.step,
        )
    }
}

private fun assertSchedules(
    expected: Map<Long, LocalCardSchedule>,
    actual: Map<Long, LocalCardSchedule>,
) {
    assertEquals(expected.size, actual.size, "Unexpected projected schedule count")
    expected.forEach { (cardId, expectedSchedule) ->
        assertEquals(expectedSchedule, actual[cardId], "Projection mismatch for an active card")
    }
    assertTrue(actual.values.all { it.stability.isFinite() && it.difficulty.isFinite() })
}

private fun writeGoFixture(
    dataset: RealReviewDataset,
    expected: Map<Long, LocalCardSchedule>,
) {
    val destination = System.getenv(GoFixtureEnvironment)?.takeIf(String::isNotBlank) ?: return
    val reviewsByCard = dataset.reviews.groupBy(SyncReview::sourceCardId)
    val fixtureJson = Json { encodeDefaults = true }
    File(destination).bufferedWriter().use { writer ->
        writer.appendLine(
            fixtureJson.encodeToString(
                GoFixtureHeader(
                    schemaVersion = 1,
                    sourceFingerprint = dataset.fingerprint,
                    caseCount = expected.size,
                    reviewCount = dataset.reviews.size,
                ),
            ),
        )
        expected.toSortedMap().forEach { (cardId, schedule) ->
            writer.appendLine(
                fixtureJson.encodeToString(
                    GoFixtureCase(
                        cardId = cardId,
                        reviews = checkNotNull(reviewsByCard[cardId]).map {
                            GoFixtureReview(reviewId = it.reviewId, rating = it.ease)
                        },
                        expected = schedule,
                    ),
                ),
            )
        }
    }
}

private fun writeReport(report: RealAcceptanceReport) {
    val destination = System.getenv(ReportEnvironment)?.takeIf(String::isNotBlank) ?: return
    File(destination).writeText(
        buildString {
            appendLine("result=passed")
            appendLine("source_fingerprint_sha256=${report.sourceFingerprint}")
            appendLine("active_cards=${report.activeCards}")
            appendLine("total_review_rows=${report.totalReviewRows}")
            appendLine("qualifying_review_rows=${report.qualifyingReviewRows}")
            appendLine("active_qualifying_reviews=${report.activeQualifyingReviews}")
            appendLine("reviewed_active_cards=${report.reviewedActiveCards}")
            appendLine("projected_schedules=${report.projectedSchedules}")
            appendLine("excluded_non_rating_rows=${report.excludedNonRatingRows}")
            appendLine("excluded_deleted_card_rows=${report.excludedDeletedCardRows}")
            appendLine("direct_replay_ms=${report.replayMillis}")
            appendLine("persistence_and_restart_ms=${report.persistenceMillis}")
            appendLine("total_ms=${report.totalMillis}")
        },
    )
}

private fun Connection.singleLong(sql: String): Long =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next()) { "Aggregate query returned no row" }
            rows.getLong(1)
        }
    }

private fun MessageDigest.updateLong(value: Long) {
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
}

private fun MessageDigest.updateInt(value: Int) {
    for (shift in 24 downTo 0 step 8) update((value ushr shift).toByte())
}

private fun acceptanceNoteGuid(noteId: Long): String = "acceptance-note-$noteId"

private const val CollectionEnvironment = "KELMA_ANKI_COLLECTION"
private const val RequiredEnvironment = "KELMA_REQUIRE_REAL_COLLECTION_ACCEPTANCE"
private const val ReportEnvironment = "KELMA_FSRS_ACCEPTANCE_REPORT"
private const val GoFixtureEnvironment = "KELMA_FSRS_GO_FIXTURE"
private const val AcceptanceDeckName = "Real collection acceptance"
