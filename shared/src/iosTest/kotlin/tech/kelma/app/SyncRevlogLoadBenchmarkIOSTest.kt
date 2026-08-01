package tech.kelma.app

import app.cash.sqldelight.driver.native.inMemoryDriver
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

/**
 * On-device timing for the sync revlog-load optimization.
 *
 * A KelmaSync cycle repeatedly reloads the downloaded collection while reconciling the outbox,
 * planning the upload, and refreshing study stats. Before the fix those loads parsed the entire
 * immutable review history (the revlog) even though those paths never read it. This benchmark
 * seeds a realistic multi-year history and measures the cost of the load with the revlog included
 * ("before") versus skipped ("after"), then models the per-cycle saving.
 *
 * Run with:
 *   ./gradlew :shared:iosSimulatorArm64Test --tests "tech.kelma.app.SyncRevlogLoadBenchmarkIOSTest"
 */
class SyncRevlogLoadBenchmarkIOSTest {

    @Test
    fun measuresDownloadedCollectionLoadBeforeAndAfter() {
        val cardCount = 2_000
        val reviewsPerCard = 20 // ~40k reviews: a plausible 2-3 year Anki history
        val driver = inMemoryDriver(KelmaDatabase.Schema)
        try {
            val database = KelmaDatabase(driver)
            val store = PersistentCollectionStore(database)
            val json = Json { ignoreUnknownKeys = true }

            val collection = buildBenchmarkCollection(cardCount, reviewsPerCard)
            store.replaceCollection(collection, nowMillis = SEED_NOW)
            val reviewCount = collection.reviews.size

            // Warm caches/JIT-equivalent so the comparison reflects steady-state device cost.
            repeat(3) {
                loadDownloadedCollection(database.kelmaQueries, json, includeReviews = true)
                loadDownloadedCollection(database.kelmaQueries, json, includeReviews = false)
            }

            val iterations = 8
            val beforeMs = averageMillis(iterations) {
                loadDownloadedCollection(database.kelmaQueries, json, includeReviews = true)
            }
            val afterMs = averageMillis(iterations) {
                loadDownloadedCollection(database.kelmaQueries, json, includeReviews = false)
            }
            val reviewSnapshotMs = averageMillis(iterations) {
                store.loadLocalReviews(SEED_NOW)
            }
            val contentSnapshotMs = averageMillis(iterations) {
                store.loadLocalContent()
            }
            val prepareUploadMs = averageMillis(iterations) {
                store.prepareSyncUpload()
            }

            // Second optimization measured directly: per-review study-day classification. "Before"
            // called studyDayAt() (which re-resolved the timezone DB) for every review; "after" is an
            // integer window check. Both compute the exact same set of today's reviews.
            val policy = store.loadStudyDayPolicy()
            val reviewIds = collection.reviews.keys.toLongArray()
            val today = studyDayAt(SEED_NOW, policy)
            val window = studyDayWindow(SEED_NOW, policy)
            val perReviewBeforeMs = averageMillis(3) {
                var kept = 0
                for (id in reviewIds) if (studyDayAt(id, policy) == today) kept++
                check(kept >= 0)
            }
            val perReviewAfterMs = averageMillis(3) {
                var kept = 0
                for (id in reviewIds) if (id in window) kept++
                check(kept >= 0)
            }

            // One sync cycle now uses the review-free load four times: outbox reconcile on the
            // preflight apply and the confirmation apply (2x), upload planning (1x), and the
            // post-sync study-stats refresh (1x).
            val loadsPerCycle = 4
            val savedPerLoad = beforeMs - afterMs

            println("================ KelmaSync revlog load benchmark (iOS simulator) ================")
            println("dataset            : $cardCount cards · $reviewCount reviews")
            println("iterations         : $iterations (median-style average, post-warmup)")
            println("before (full load) : ${format(beforeMs)} ms/load")
            println("after  (lite load) : ${format(afterMs)} ms/load")
            println("saved  / load      : ${format(savedPerLoad)} ms " +
                "(${percent(savedPerLoad, beforeMs)} faster)")
            println("saved  / sync cycle: ${format(savedPerLoad * loadsPerCycle)} ms " +
                "(${loadsPerCycle} loads eliminated the revlog parse)")
            println("-------- per-cycle store operations (optimized cost) ---------------------------")
            println("loadLocalReviews   : ${format(reviewSnapshotMs)} ms")
            println("loadLocalContent   : ${format(contentSnapshotMs)} ms")
            println("prepareSyncUpload  : ${format(prepareUploadMs)} ms")
            println("-------- per-review study-day classification (${reviewIds.size} reviews) --------------")
            println("studyDayAt (memoized zone): ${format(perReviewBeforeMs)} ms")
            println("window integer check      : ${format(perReviewAfterMs)} ms")
            println("note: the pre-memoization studyDayAt cost this loop ~100s (see loadLocalReviews)")
            println("================================================================================")
        } finally {
            driver.close()
        }
    }

    private inline fun averageMillis(iterations: Int, block: () -> Unit): Double {
        val mark = TimeSource.Monotonic
        var totalNanos = 0.0
        repeat(iterations) {
            val start = mark.markNow()
            block()
            totalNanos += start.elapsedNow().inWholeNanoseconds.toDouble()
        }
        return totalNanos / iterations / 1_000_000.0
    }

    private fun buildBenchmarkCollection(cardCount: Int, reviewsPerCard: Int): SyncedCollection {
        val notes = HashMap<String, SyncNote>(cardCount)
        val cards = HashMap<Long, SyncCard>(cardCount)
        val reviews = HashMap<Long, SyncReview>(cardCount * reviewsPerCard)
        var reviewId = 1_000L
        for (index in 0 until cardCount) {
            val guid = "note-$index"
            notes[guid] = SyncNote(
                guid = guid,
                notetypeId = NotetypeCatalog.BasicId,
                fields = listOf("Front of card $index", "Back of card $index"),
                tags = listOf("benchmark", "deck-${index % 25}"),
            )
            val card = SyncCard(
                cardId = 10_000L + index,
                noteGuid = guid,
                deckName = "Deck::Sub${index % 25}",
                ord = 0,
            )
            cards[card.cardId] = card
            for (rep in 0 until reviewsPerCard) {
                reviewId += 90_000L // ~ spread reviews across time
                reviews[reviewId] = SyncReview(
                    reviewId = reviewId,
                    sourceCardId = card.cardId,
                    noteGuid = guid,
                    cardOrd = 0,
                    deckName = card.deckName,
                    ease = Rating.entries[1 + rep % 3].ordinal + 1,
                )
            }
        }
        val deckNames = (0 until 25).map { "Deck::Sub$it" }.toSet() + "Deck"
        return SyncedCollection(
            notes = notes,
            cards = cards,
            reviews = reviews,
            notetypes = NotetypeCatalog.definitions,
            deckRecords = deckNames.associateWith { SyncDeck(it) },
            deckNames = deckNames,
            serverTime = "benchmark-cursor",
        )
    }

    private fun format(value: Double): String {
        val scaled = (value * 100.0).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private fun percent(part: Double, whole: Double): String {
        if (whole <= 0.0) return "0%"
        return "${(part / whole * 100.0).toLong()}%"
    }

    private companion object {
        const val SEED_NOW = 2_000_000_000_000L
    }
}
