package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class SyncWorkflowTest {
    @Test
    fun renameUsesAuthoritativeCardClockSoCardProjectionMovesForImmersion() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val serverTime = "2027-01-15T08:00:00.000Z"
        val initial = collectionWithCards(1L).copy(
            cards = mapOf(
                1L to SyncCard(
                    1,
                    "note-1",
                    "Deck",
                    clientModifiedAt = "2027-01-15T08:04:00.000Z",
                ),
            ),
            serverTime = serverTime,
        )
        store.replaceCollection(initial)
        store.renameLocalDeck("Deck", "Renamed", nowMillis = 1_000L)
        val service = RenamePropagationService(initial)
        val progress = mutableListOf<SyncProgress>()
        var now = 0L

        val completed = runSyncCycle(
            service,
            store,
            "token",
            initial,
            onProgress = { progress += it },
            clock = { now += 100L; now },
        )

        assertEquals("2027-01-15T08:04:00.000Z", service.cardTimestamp)
        assertEquals("Renamed", completed.report.collection.cards.getValue(1L).deckName)
        assertEquals("Renamed", service.immersionDeckName)
        assertTrue(progress.any { it.phase == "PREFLIGHT" && "Server pull finished" in it.message })
        assertTrue(progress.any { it.phase == "LOCAL" && "Applied confirmation" in it.message })
        assertTrue(progress.last().message.contains("total"))
        driver.close()
    }

    @Test
    fun schedulerProfileUploadIsConfirmedByAuthoritativeProfilePull() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val initial = SyncedCollection(serverTime = "profile-cursor")
        store.saveSignedInState(
            StoredSyncAuth("token", "client", "https://acceptance.kelma.invalid", "profile@example.com"),
            initial,
        )
        store.applyAccountSchedulerProfile(
            SchedulerProfileSettings(
                parameterSource = SchedulerProfileSource.Manual,
                desiredRetention = 0.92,
                retentionSource = SchedulerProfileSource.Manual,
            ),
            publishToCloud = true,
            nowMillis = 1_000,
        )
        val service = ProfileSyncService(initial)

        val completed = runSyncCycle(service, store, "token", initial)

        assertEquals(2, service.profilePullCount)
        assertEquals(0.92, service.uploadedCandidate?.desiredRetention)
        assertEquals(SchedulerProfileSyncStatus.Current, completed.schedulerProfile.syncStatus)
        assertEquals(1, completed.schedulerProfile.cloud?.version)
        assertNull(store.prepareSyncUpload().schedulerProfile)
        driver.close()
    }

    @Test
    fun firstTimezoneAwareSyncInitializesAndThenFetchesAccountStudyDayPolicy() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.observeCloudStudyDayPolicy(
            AccountStudyDayPolicy(version = 1, timezoneId = "Europe/Paris", dayStartHour = 3),
        )
        val collection = SyncedCollection(serverTime = "policy-cursor")
        val service = PolicySyncService(collection)

        val first = runSyncCycle(service, store, "token", collection)
        val second = runSyncCycle(service, store, "token", collection)

        assertEquals(1, service.policyPutCount)
        assertEquals("Europe/Paris", service.uploadedCandidate?.timezoneId)
        assertEquals(3, service.uploadedCandidate?.dayStartHour)
        assertEquals(first.studyDayPolicy, second.studyDayPolicy)
        assertEquals(first.studyDayPolicy, store.loadStudyDayPolicy())
        driver.close()
    }

    @Test
    fun authenticatedAccountInitializationStreamsSyncProgressImmediately() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val vault = InMemoryCredentialVault()
        val store = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault)
        val service = ProfileSyncService(SyncedCollection(serverTime = "signed-in-cursor"))
        val progress = mutableListOf<SyncProgress>()

        val initialized = initializeAccountSession(
            store,
            service,
            PendingAccountSignIn("account@example.com", LoginResponse("token", "client")),
            progress::add,
        )

        assertEquals("token", store.load().auth?.token)
        assertEquals("signed-in-cursor", initialized.collection.serverTime)
        assertTrue(progress.first().phase == "PREFLIGHT")
        assertTrue(progress.last().phase == "COMPLETE")
        driver.close()
    }

    @Test
    fun incrementalReviewPullReplaysOnlyAffectedCardHistory() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val scheduler = CountingSchedulingEngine()
        val store = PersistentCollectionStore(KelmaDatabase(driver), scheduler = scheduler)
        val cards = listOf(
            SyncCard(10L, "changed-note", "Deck"),
            SyncCard(20L, "unchanged-note", "Deck"),
        )
        fun review(reviewId: Long, card: SyncCard) = SyncReview(
            reviewId = reviewId,
            sourceCardId = card.cardId,
            noteGuid = card.noteGuid,
            cardOrd = card.ord,
            deckName = card.deckName,
            ease = Rating.Good.ordinal + 1,
        )
        val initialReviews = listOf(review(1_000L, cards[0]), review(2_000L, cards[1]))
        val initial = SyncedCollection(
            notes = cards.associate { it.noteGuid to SyncNote(it.noteGuid) },
            cards = cards.associateBy(SyncCard::cardId),
            reviews = initialReviews.associateBy(SyncReview::reviewId),
            deckNames = setOf("Deck"),
            serverTime = "before-review",
        )
        val policy = AccountStudyDayPolicy.systemDefault().copy(version = 1L)
        store.observeCloudStudyDayPolicy(policy)
        store.replaceCollection(initial)
        scheduler.reviewCalls = 0
        val latestReview = review(3_000L, cards[0])
        val latest = initial.copy(
            reviews = (initialReviews + latestReview).associateBy(SyncReview::reviewId),
            serverTime = "after-review",
        )

        runSyncCycle(IncrementalReplayService(latest, policy), store, "token", initial)

        assertEquals(2, scheduler.reviewCalls)
        assertEquals(2, store.loadLocalReviews(3_000L).schedules.getValue(cards[0].cardId).repetitions)
        assertEquals(1, store.loadLocalReviews(3_000L).schedules.getValue(cards[1].cardId).repetitions)
        driver.close()
    }

    @Test
    fun contentOnlyPullDoesNotReplayReviewHistory() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val scheduler = CountingSchedulingEngine()
        val store = PersistentCollectionStore(KelmaDatabase(driver), scheduler = scheduler)
        val card = SyncCard(30L, "content-note", "Deck")
        val review = SyncReview(
            reviewId = 1_000L,
            sourceCardId = card.cardId,
            noteGuid = card.noteGuid,
            cardOrd = card.ord,
            deckName = card.deckName,
            ease = Rating.Good.ordinal + 1,
        )
        val initial = SyncedCollection(
            notes = mapOf(card.noteGuid to SyncNote(card.noteGuid, fields = listOf("before"))),
            cards = mapOf(card.cardId to card),
            reviews = mapOf(review.reviewId to review),
            deckNames = setOf("Deck"),
            serverTime = "before-content",
        )
        val policy = AccountStudyDayPolicy.systemDefault().copy(version = 1L)
        store.observeCloudStudyDayPolicy(policy)
        val originalSchedule = store.replaceCollection(initial).schedules.getValue(card.cardId)
        scheduler.reviewCalls = 0
        val latest = initial.copy(
            notes = mapOf(card.noteGuid to initial.notes.getValue(card.noteGuid).copy(fields = listOf("after"))),
            serverTime = "after-content",
        )

        val completed = runSyncCycle(IncrementalReplayService(latest, policy), store, "token", initial)

        assertEquals(0, scheduler.reviewCalls)
        assertEquals(originalSchedule, completed.localReviews.schedules.getValue(card.cardId))
        assertEquals(listOf("after"), completed.report.collection.notes.getValue(card.noteGuid).fields)
        driver.close()
    }

    @Test
    fun pullBeforeDeleteIncludesCardsAddedRemotelySinceTheLastCursor() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val initial = collectionWithCards(1L)
        store.replaceCollection(initial)
        store.deleteLocalDeck("Deck", nowMillis = 1_000L)
        val service = RacingDeleteService(collectionWithCards(1L, 2L))

        val completed = runSyncCycle(service, store, "token", initial)

        assertEquals(setOf(1L, 2L), service.uploadedPlan?.decks?.single()?.deleteRequest?.cards?.toSet())
        assertEquals(2, service.pullCount)
        assertTrue(completed.report.collection.cards.isEmpty())
        assertTrue(completed.conflicts.isEmpty())
        assertTrue(store.loadLocalContent().deckOverrides.isEmpty())
        driver.close()
    }

    private fun collectionWithCards(vararg ids: Long): SyncedCollection = SyncedCollection(
        notes = ids.associate { id -> "note-$id" to SyncNote("note-$id", fields = listOf("front", "back")) },
        cards = ids.associateWith { id -> SyncCard(id, "note-$id", "Deck") },
        deckRecords = mapOf("Deck" to SyncDeck("Deck")),
        deckNames = setOf("Deck"),
        serverTime = "cursor-${ids.size}",
    )
}

private class CountingSchedulingEngine : SchedulingEngine {
    var reviewCalls: Int = 0

    override fun review(
        card: SyncCard,
        previous: LocalCardSchedule?,
        rating: Rating,
        reviewedAtMillis: Long,
        serverLastReviewAtMillis: Long?,
        options: DeckOptions,
    ): LocalCardSchedule {
        reviewCalls++
        return FsrsScheduler.review(card, previous, rating, reviewedAtMillis, serverLastReviewAtMillis, options)
    }
}

private class IncrementalReplayService(
    private val latest: SyncedCollection,
    private val policy: AccountStudyDayPolicy,
) : KelmaSyncService {
    override suspend fun login(username: String, password: String) = LoginResponse("token", "client")

    override suspend fun pull(token: String, current: SyncedCollection): PullReport =
        PullReport(latest, downloaded = 1, removed = 0)

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult = SyncPushResult()

    override suspend fun getStudyDayPolicy(token: String): AccountStudyDayPolicy = policy
}

private class RenamePropagationService(
    private val initial: SyncedCollection,
) : KelmaSyncService {
    private var pullCount = 0
    var cardTimestamp: String? = null
    var immersionDeckName: String = "Deck"

    override suspend fun login(username: String, password: String) = LoginResponse("token", "client")

    override suspend fun pull(token: String, current: SyncedCollection): PullReport {
        pullCount++
        val collection = if (pullCount == 1) {
            initial
        } else {
            initial.copy(
                cards = mapOf(1L to initial.cards.getValue(1L).copy(deckName = immersionDeckName)),
                deckRecords = mapOf(immersionDeckName to SyncDeck(immersionDeckName)),
                deckNames = setOf(immersionDeckName),
                serverTime = "2027-01-15T08:00:01.000Z",
            )
        }
        return PullReport(collection, downloaded = if (pullCount == 1) 0 else 2, removed = 0)
    }

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult {
        val rename = plan.decks.single()
        cardTimestamp = rename.cards.single().second.clientModifiedAt
        val existingTimestamp = initial.cards.getValue(1L).clientModifiedAt
        if ((rfc3339ToEpochMillis(cardTimestamp.orEmpty()) ?: 0L) >=
            (rfc3339ToEpochMillis(existingTimestamp) ?: Long.MAX_VALUE)) {
            immersionDeckName = "Renamed"
        }
        return SyncPushResult(uploadedDeckSources = setOf("Deck"))
    }
}

private class PolicySyncService(private val collection: SyncedCollection) : KelmaSyncService {
    var policyPutCount = 0
    var uploadedCandidate: StudyDayPolicyCandidate? = null
    private var policy = AccountStudyDayPolicy()

    override suspend fun login(username: String, password: String): LoginResponse =
        LoginResponse("token", "client")

    override suspend fun pull(token: String, current: SyncedCollection): PullReport =
        PullReport(collection, downloaded = 0, removed = 0)

    override suspend fun getStudyDayPolicy(token: String): AccountStudyDayPolicy = policy

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult = SyncPushResult()

    override suspend fun putStudyDayPolicy(
        token: String,
        candidate: StudyDayPolicyCandidate,
    ): AccountStudyDayPolicy {
        policyPutCount++
        uploadedCandidate = candidate
        return AccountStudyDayPolicy(
            version = candidate.baseVersion + 1L,
            timezoneId = candidate.timezoneId,
            dayStartHour = candidate.dayStartHour,
            idempotencyKey = candidate.idempotencyKey,
        ).also { policy = it }
    }
}

private class ProfileSyncService(private val collection: SyncedCollection) : KelmaSyncService {
    var profilePullCount = 0
    var uploadedCandidate: SchedulerProfileCandidate? = null
    private var accepted: SchedulerProfileResponse? = null

    override suspend fun login(username: String, password: String): LoginResponse =
        LoginResponse("token", "client")

    override suspend fun pull(token: String, current: SyncedCollection): PullReport =
        PullReport(collection, downloaded = 0, removed = 0)

    override suspend fun getSchedulerProfile(token: String): SchedulerProfileResponse {
        profilePullCount++
        return accepted ?: syncProfileResponse(version = 0)
    }

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult {
        val candidate = requireNotNull(plan.schedulerProfile)
        uploadedCandidate = candidate
        val response = syncProfileResponse(
            version = 1,
            idempotencyKey = candidate.idempotencyKey,
            desiredRetention = candidate.desiredRetention,
        )
        accepted = response
        return SyncPushResult(acknowledgedSchedulerProfile = response)
    }
}

private fun syncProfileResponse(
    version: Long,
    idempotencyKey: String = "",
    desiredRetention: Double = 0.90,
): SchedulerProfileResponse = SchedulerProfileResponse(
    profile = CloudSchedulerProfile(
        version = version,
        schedulerVersion = "6.0.0-kelma.1",
        idempotencyKey = idempotencyKey,
        desiredRetention = desiredRetention,
        retentionSource = if (version == 0L) SchedulerProfileSource.Default else SchedulerProfileSource.Manual,
        configHash = "sync-$version-$desiredRetention",
    ),
)

private class RacingDeleteService(private val latest: SyncedCollection) : KelmaSyncService {
    var pullCount = 0
    var uploadedPlan: SyncUploadPlan? = null

    override suspend fun login(username: String, password: String): LoginResponse =
        LoginResponse("token", "client")

    override suspend fun pull(token: String, current: SyncedCollection): PullReport {
        pullCount++
        return if (pullCount == 1) {
            PullReport(latest, downloaded = 1, removed = 0)
        } else {
            PullReport(SyncedCollection(serverTime = "cursor-deleted"), downloaded = 0, removed = 5)
        }
    }

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult {
        uploadedPlan = plan
        return SyncPushResult(uploadedDeckSources = setOf("Deck"))
    }
}
