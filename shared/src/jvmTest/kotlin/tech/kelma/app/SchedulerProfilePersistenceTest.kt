package tech.kelma.app

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class SchedulerProfilePersistenceTest {
    @Test
    fun profileUploadSurvivesRestartAndNeedsAcknowledgementPlusConfirmingPull() {
        val directory = Files.createTempDirectory("kelma-profile-outbox").toFile()
        val databaseFile = directory.resolve("kelma.db")
        try {
            var driver = openDesktopDatabase(databaseFile)
            var store = PersistentCollectionStore(KelmaDatabase(driver))
            store.saveSignedInState(TestAuth, SyncedCollection(), ProfileNow)
            store.observeCloudSchedulerProfile(profileResponse(version = 0), ProfileNow)

            val settings = SchedulerProfileSettings(
                parameterSource = SchedulerProfileSource.Manual,
                desiredRetention = 0.94,
                retentionSource = SchedulerProfileSource.Manual,
            )
            val applied = store.applyAccountSchedulerProfile(
                settings,
                publishToCloud = true,
                nowMillis = ProfileNow + 1,
            )
            assertEquals(1, applied.local.version)
            assertEquals(SchedulerProfileSyncStatus.Pending, applied.syncStatus)
            val firstPlan = assertNotNull(store.prepareSyncUpload().schedulerProfile)
            assertEquals(0, firstPlan.baseProfileVersion)
            assertEquals(0.94, firstPlan.desiredRetention)

            driver.close()
            driver = openDesktopDatabase(databaseFile)
            store = PersistentCollectionStore(KelmaDatabase(driver))
            val restartedPlan = assertNotNull(store.prepareSyncUpload().schedulerProfile)
            assertEquals(firstPlan.idempotencyKey, restartedPlan.idempotencyKey)

            val acknowledged = profileResponse(
                version = 1,
                idempotencyKey = firstPlan.idempotencyKey,
                desiredRetention = 0.94,
            )
            store.applySyncPushResult(
                SyncPushResult(acknowledgedSchedulerProfile = acknowledged),
            )
            assertTrue(store.prepareSyncUpload().isEmpty)
            assertEquals(
                SchedulerProfileSyncStatus.AwaitingConfirmation,
                store.loadSchedulerProfile().syncStatus,
            )

            driver.close()
            driver = openDesktopDatabase(databaseFile)
            store = PersistentCollectionStore(KelmaDatabase(driver))
            assertEquals(
                SchedulerProfileSyncStatus.AwaitingConfirmation,
                store.loadSchedulerProfile().syncStatus,
            )
            store.observeCloudSchedulerProfile(acknowledged, ProfileNow + 2)
            val confirmed = store.loadSchedulerProfile()
            assertEquals(SchedulerProfileSyncStatus.Current, confirmed.syncStatus)
            assertEquals(1, confirmed.cloud?.version)
            assertNull(store.prepareSyncUpload().schedulerProfile)
            driver.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun conflictKeepsLocalProfileAndRequiresExplicitResolution() {
        val directory = Files.createTempDirectory("kelma-profile-conflict").toFile()
        val driver = openDesktopDatabase(directory.resolve("kelma.db"))
        try {
            val store = PersistentCollectionStore(KelmaDatabase(driver))
            store.saveSignedInState(TestAuth, SyncedCollection(), ProfileNow)
            store.observeCloudSchedulerProfile(profileResponse(version = 1), ProfileNow)
            val local = store.applyAccountSchedulerProfile(
                SchedulerProfileSettings(
                    parameterSource = SchedulerProfileSource.Manual,
                    desiredRetention = 0.93,
                    retentionSource = SchedulerProfileSource.Manual,
                ),
                publishToCloud = true,
                nowMillis = ProfileNow + 1,
            ).local
            val original = assertNotNull(store.prepareSyncUpload().schedulerProfile)
            val server = profileResponse(version = 2, desiredRetention = 0.88)
            store.applySyncPushResult(
                SyncPushResult(
                    conflicts = listOf(
                        SyncUploadConflict(
                            SchedulerProfileConflictKind,
                            original.idempotencyKey,
                            ContractProfileJson.encodeToString(server.profile),
                        ),
                    ),
                ),
            )

            val conflicted = store.loadSchedulerProfile()
            assertEquals(SchedulerProfileSyncStatus.Conflict, conflicted.syncStatus)
            assertEquals(local, conflicted.local)
            assertEquals(2, conflicted.cloud?.version)
            assertEquals(SchedulerProfileConflictKind, store.loadSyncConflicts().single().kind)
            store.applyAccountSchedulerProfile(
                SchedulerProfileSettings(
                    parameterSource = SchedulerProfileSource.Manual,
                    desiredRetention = 0.91,
                    retentionSource = SchedulerProfileSource.Manual,
                ),
                publishToCloud = false,
                nowMillis = ProfileNow + 2,
            )

            store.resolveSyncConflict(store.loadSyncConflicts().single(), keepLocal = true, ProfileNow + 3)
            val retry = assertNotNull(store.prepareSyncUpload().schedulerProfile)
            assertEquals(2, retry.baseProfileVersion)
            assertNotEquals(original.idempotencyKey, retry.idempotencyKey)
            assertEquals(0.91, retry.desiredRetention)

            store.applySyncPushResult(
                SyncPushResult(
                    conflicts = listOf(
                        SyncUploadConflict(
                            SchedulerProfileConflictKind,
                            retry.idempotencyKey,
                            ContractProfileJson.encodeToString(server.profile),
                        ),
                    ),
                ),
            )
            store.resolveSyncConflict(store.loadSyncConflicts().single(), keepLocal = false, ProfileNow + 4)
            assertEquals(SchedulerProfileSyncStatus.Current, store.loadSchedulerProfile().syncStatus)
            assertEquals(0.91, store.loadSchedulerProfile().local.settings.desiredRetention)
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun cloudApplyIsExplicitAndLegacyProfileCannotBePublished() {
        val directory = Files.createTempDirectory("kelma-profile-apply").toFile()
        val driver = openDesktopDatabase(directory.resolve("kelma.db"))
        try {
            val store = PersistentCollectionStore(KelmaDatabase(driver))
            assertFailsWith<IllegalArgumentException> {
                store.applyAccountSchedulerProfile(
                    SchedulerProfileSettings.fromDeckOptions(
                        DeckOptions(
                            schedulerAlgorithm = SchedulerAlgorithm.Fsrs5,
                            fsrsParameters = DefaultFsrs5Parameters.mapIndexed { index, value ->
                                if (index == 0) value + 0.01 else value
                            },
                        ),
                    ),
                    publishToCloud = false,
                    nowMillis = ProfileNow,
                )
            }
            store.saveSignedInState(TestAuth, profileCollection(), ProfileNow)
            val before = store.load(ProfileNow).localReviews.schedules.getValue(1).dueAtMillis
            store.observeCloudSchedulerProfile(
                profileResponse(version = 3, desiredRetention = 0.96),
                ProfileNow,
            )
            assertEquals(before, store.load(ProfileNow).localReviews.schedules.getValue(1).dueAtMillis)
            assertEquals(0.90, store.loadSchedulerProfile().local.settings.desiredRetention)
            val applied = store.applyCloudSchedulerProfileLocally(ProfileNow + 1)
            val after = store.load(ProfileNow).localReviews.schedules.getValue(1).dueAtMillis
            assertEquals(0.96, applied.local.settings.desiredRetention)
            assertEquals(1, applied.local.version)
            assertTrue(after < before, "Higher retention should shorten the rebuilt interval")
            assertTrue(store.prepareSyncUpload().isEmpty)
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun accountProfileDoesNotReplaceExplicitDeckSchedulerOptions() {
        val directory = Files.createTempDirectory("kelma-profile-deck-override").toFile()
        val driver = openDesktopDatabase(directory.resolve("kelma.db"))
        try {
            val store = PersistentCollectionStore(KelmaDatabase(driver))
            store.saveSignedInState(TestAuth, profileCollection(), ProfileNow)
            store.saveDeckOptions(
                "Deck",
                DeckOptions(
                    desiredRetention = 0.80,
                    schedulerAlgorithm = SchedulerAlgorithm.Fsrs6,
                ),
                ProfileNow,
            )
            val before = store.load(ProfileNow).localReviews.schedules.getValue(1)
            store.applyAccountSchedulerProfile(
                SchedulerProfileSettings(
                    desiredRetention = 0.99,
                    retentionSource = SchedulerProfileSource.Manual,
                ),
                publishToCloud = false,
                nowMillis = ProfileNow + 1,
            )
            val after = store.load(ProfileNow).localReviews.schedules.getValue(1)
            assertEquals(before, after)
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun accountChangeAndSignOutClearProfileState() {
        val directory = Files.createTempDirectory("kelma-profile-account").toFile()
        val driver = openDesktopDatabase(directory.resolve("kelma.db"))
        try {
            val store = PersistentCollectionStore(KelmaDatabase(driver))
            store.saveSignedInState(TestAuth, SyncedCollection(), ProfileNow)
            store.observeCloudSchedulerProfile(profileResponse(version = 2), ProfileNow)
            store.applyAccountSchedulerProfile(
                SchedulerProfileSettings(parameterSource = SchedulerProfileSource.Manual),
                publishToCloud = true,
                nowMillis = ProfileNow + 1,
            )
            store.saveSignedInState(
                TestAuth.copy(username = "other@example.com"),
                SyncedCollection(),
                ProfileNow + 2,
            )
            assertEquals(0, store.loadSchedulerProfile().local.version)
            assertNull(store.loadSchedulerProfile().cloud)
            assertTrue(store.prepareSyncUpload().isEmpty)
            assertEquals(
                1,
                store.applyAccountSchedulerProfile(
                    SchedulerProfileSettings(parameterSource = SchedulerProfileSource.Manual),
                    publishToCloud = false,
                    nowMillis = ProfileNow + 3,
                ).local.version,
            )

            store.observeCloudSchedulerProfile(profileResponse(version = 3), ProfileNow + 3)
            store.clearAll()
            assertEquals(SchedulerProfileState(), store.loadSchedulerProfile())
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun publishingRequiresAnAccount() {
        val directory = Files.createTempDirectory("kelma-profile-auth").toFile()
        val driver = openDesktopDatabase(directory.resolve("kelma.db"))
        try {
            val store = PersistentCollectionStore(KelmaDatabase(driver))
            assertFailsWith<IllegalArgumentException> {
                store.applyAccountSchedulerProfile(
                    SchedulerProfileSettings(parameterSource = SchedulerProfileSource.Manual),
                    publishToCloud = true,
                    nowMillis = ProfileNow,
                )
            }
            assertEquals(0, store.loadSchedulerProfile().local.version)
        } finally {
            driver.close()
            directory.deleteRecursively()
        }
    }
}

private fun profileResponse(
    version: Long,
    idempotencyKey: String = "",
    desiredRetention: Double = 0.90,
): SchedulerProfileResponse = SchedulerProfileResponse(
    profile = CloudSchedulerProfile(
        version = version,
        schedulerVersion = "6.0.0-kelma.1",
        idempotencyKey = idempotencyKey,
        desiredRetention = desiredRetention,
        retentionSource = if (desiredRetention == 0.90) {
            SchedulerProfileSource.Default
        } else {
            SchedulerProfileSource.Manual
        },
        configHash = "config-$version-$desiredRetention",
    ),
)

private fun profileCollection(): SyncedCollection = SyncedCollection(
    cards = mapOf(1L to SyncCard(1, "profile-note", "Deck")),
    reviews = listOf(
        SyncReview(ProfileNow - 20 * MillisPerDay, 1, "profile-note", 0, "Deck", ease = 3),
        SyncReview(ProfileNow - 20 * MillisPerDay + 600_000, 1, "profile-note", 0, "Deck", ease = 3),
        SyncReview(ProfileNow, 1, "profile-note", 0, "Deck", ease = 3),
    ).associateBy(SyncReview::reviewId),
    deckNames = setOf("Deck"),
)

private val TestAuth = StoredSyncAuth(
    token = "profile-token",
    clientId = "profile-client",
    endpoint = "https://acceptance.kelma.invalid",
    username = "profile@example.com",
)
private const val ProfileNow = 1_800_000_000_000L
private val ContractProfileJson = kotlinx.serialization.json.Json { explicitNulls = false }
