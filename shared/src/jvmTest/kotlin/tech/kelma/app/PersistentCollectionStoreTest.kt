package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import tech.kelma.db.KelmaDatabase

class PersistentCollectionStoreTest {
    @Test
    fun syncLogPersistsAndCanBeCleared() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))

        store.appendSyncLog(SyncProgress(SyncLogLevel.Warning, "DECK", "delete: german34"), 12_345L)
        val restored = PersistentCollectionStore(KelmaDatabase(driver)).loadSyncLog()

        assertEquals(1, restored.size)
        assertEquals(12_345L, restored.single().occurredAtMillis)
        assertEquals(SyncLogLevel.Warning, restored.single().level)
        assertEquals("delete: german34", restored.single().message)
        store.clearSyncLog()
        assertTrue(store.loadSyncLog().isEmpty())
        driver.close()
    }

    @Test
    fun syncLogNeverPersistsOrDisplaysPresignedUrlCredentials() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val leaked = "timeout https://r2.invalid/file?X-Amz-Credential=secret&X-Amz-Signature=private"

        store.appendSyncLog(SyncProgress(SyncLogLevel.Error, "FAILED", leaked), 1_000L)
        database.kelmaQueries.insertSyncLogEntry(2_000L, "Error", "LEGACY", leaked)

        val displayed = store.loadSyncLog()
        assertTrue(displayed.none { "X-Amz-" in it.message })
        val persisted = database.kelmaQueries.selectSyncLogEntries(10) { _, _, _, _, message -> message }
            .executeAsList()
        assertTrue("X-Amz-" !in persisted.last())
        driver.close()
    }

    @Test
    fun liveSyncProgressUpdatesOnePersistentLogRow() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))

        store.appendSyncLog(SyncProgress(phase = "CARDS", message = "Uploading cards · 0 / 1,200"), 1_000L)
        store.appendSyncLog(
            SyncProgress(
                level = SyncLogLevel.Success,
                phase = "CARDS",
                message = "Finished cards · 1,200 accepted",
                replaceLatest = true,
            ),
            2_000L,
        )

        val entry = store.loadSyncLog().single()
        assertEquals(2_000L, entry.occurredAtMillis)
        assertEquals(SyncLogLevel.Success, entry.level)
        assertEquals("Finished cards · 1,200 accepted", entry.message)
        driver.close()
    }

    @Test
    fun migrationTwentyRemovesLegacyIdentifierPerRowSyncLogs() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.appendSyncLog(SyncProgress(phase = "NOTE", message = "upsert: private-guid"), 1_000L)
        store.appendSyncLog(SyncProgress(phase = "OUTBOX", message = "2 notes"), 2_000L)

        KelmaDatabase.Schema.migrate(driver, 20, 21)

        val remaining = store.loadSyncLog()
        assertEquals(listOf("OUTBOX"), remaining.map(SyncLogEntry::phase))
        driver.close()
    }

    @Test
    fun desktopOpenerMigratesTheExistingUnversionedDatabase() {
        val directory = Files.createTempDirectory("kelma-db-migration").toFile()
        val databaseFile = directory.resolve("kelma.db")
        val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
        JdbcSqliteDriver(jdbcUrl).also { legacy ->
            legacy.execute(null, "CREATE TABLE legacy_marker (value INTEGER)", 0)
            legacy.close()
        }

        val migrated = openDesktopDatabase(databaseFile)
        assertTrue(KelmaDatabase(migrated).kelmaQueries.selectLocalSchedules().executeAsList().isEmpty())
        migrated.close()
        val version = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }
        assertEquals(KelmaDatabase.Schema.version, version)
        directory.deleteRecursively()
    }

    @Test
    fun versionOneMigrationAddsTransactionalReviewTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            identifier = null,
            sql = """CREATE TABLE sync_reviews (
                review_id INTEGER, source_card_id INTEGER, note_guid TEXT, card_ord INTEGER,
                deck_name TEXT, ease INTEGER, interval_value INTEGER, last_interval INTEGER,
                factor INTEGER, taken_millis INTEGER, review_kind INTEGER, checksum TEXT, modified_at TEXT
            )""".trimIndent(),
            parameters = 0,
        )
        driver.execute(
            null,
            "CREATE TABLE sync_cards (card_id INTEGER, note_guid TEXT, ord INTEGER)",
            0,
        )
        driver.execute(
            null,
            """CREATE TABLE sync_study_days (
                day INTEGER, deck_name TEXT, new_studied INTEGER, review_studied INTEGER,
                learning_studied INTEGER, milliseconds_studied INTEGER, modified_at TEXT
            )""".trimIndent(),
            0,
        )
        driver.createLegacyAuthTable()
        driver.createLegacyMediaTable()
        KelmaDatabase.Schema.migrate(driver, 1, KelmaDatabase.Schema.version)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val change = store.recordReview(
            card = SyncCard(20, "note-1", "Deck"),
            rating = Rating.Good,
            reviewedAtMillis = 1_000_000L,
        )

        assertEquals(ReviewPhase.Learning, change.schedule.phase)
        assertEquals(change.schedule, change.snapshot.schedules[20])
        assertEquals(1L, KelmaDatabase(driver).kelmaQueries.selectBrowseIndexDirty().executeAsOne())
        driver.close()
    }

    @Test
    fun versionTwoMigrationAddsLocalContentTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE local_review_events (event_id INTEGER, card_id INTEGER)", 0)
        driver.execute(
            null,
            "CREATE TABLE sync_cards (card_id INTEGER, note_guid TEXT, ord INTEGER)",
            0,
        )
        driver.execute(
            null,
            """CREATE TABLE local_card_schedules (
                card_id INTEGER PRIMARY KEY, phase TEXT NOT NULL, due_at_ms INTEGER NOT NULL,
                stability REAL NOT NULL, difficulty REAL NOT NULL, scheduled_days INTEGER NOT NULL,
                repetitions INTEGER NOT NULL, lapses INTEGER NOT NULL, last_review_at_ms INTEGER NOT NULL
            )""".trimIndent(),
            0,
        )
        driver.createLegacyAuthTable()
        driver.createLegacyMediaTable()
        KelmaDatabase.Schema.migrate(driver, 2, KelmaDatabase.Schema.version)
        val store = PersistentCollectionStore(KelmaDatabase(driver))

        val added = store.addLocalNote(
            AddNoteDraft("Languages", "bonjour", "hello", listOf("french")),
            nowMillis = 1_000_000L,
            noteGuid = "local-migration-test",
        )

        assertEquals(1, added.content.cardCount)
        assertEquals(1, added.content.pendingSyncByDeck.getValue("Languages").added)
        assertEquals("bonjour", added.content.notes.getValue("local-migration-test").fields.first())
        assertEquals(setOf("Languages"), added.content.deckNames)
        driver.close()
    }

    @Test
    fun migrationTwentySixAddsDurableCardDueDates() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE sync_cards (card_id INTEGER PRIMARY KEY)", 0)

        KelmaDatabase.Schema.migrate(driver, 26, 27)
        val queries = KelmaDatabase(driver).kelmaQueries
        queries.upsertLocalCardDueOverride(
            noteGuid = "note",
            cardOrd = 0L,
            cardId = 42L,
            dueAtMillis = 10L * MillisPerDay,
            clientModifiedAt = 1_000L,
        )

        assertEquals(
            listOf(42L to 10L * MillisPerDay),
            queries.selectLocalCardDueOverrides { _, _, cardId, dueAt, _, _ -> cardId to dueAt }
                .executeAsList(),
        )
        driver.close()
    }

    @Test
    fun migrationTwentySevenAddsDurableNoteBuries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        KelmaDatabase.Schema.migrate(driver, 27, 28)
        val queries = KelmaDatabase(driver).kelmaQueries
        queries.upsertLocalNoteBury("note", 10L)

        assertEquals(listOf("note"), queries.selectLocalNoteBuriesForDay(10L).executeAsList())
        driver.close()
    }

    @Test
    fun migrationThirtyAddsStudyDayPolicyState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        KelmaDatabase.Schema.migrate(driver, 30, 31)
        val queries = KelmaDatabase(driver).kelmaQueries
        queries.upsertStudyDayPolicyState(
            "{\"version\":1,\"timezone_id\":\"UTC\",\"day_start_hour\":4}",
            1_000L,
        )

        assertEquals(1, queries.selectStudyDayPolicyState().executeAsList().size)
        driver.close()
    }

    @Test
    fun cardFlagsPersistAcrossRestartAndClearWithAccountData() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)

        store.setCardFlag(42L, ReviewFlag.Blue.value)

        assertEquals(ReviewFlag.Blue.value, PersistentCollectionStore(database).loadLocalContent().cardFlags[42L])
        store.setCardFlag(42L, ReviewFlag.None.value)
        assertTrue(store.loadLocalContent().cardFlags.isEmpty())
        store.setCardFlag(42L, ReviewFlag.Blue.value)
        store.clearAll()
        assertTrue(store.loadLocalContent().cardFlags.isEmpty())
        driver.close()
    }

    @Test
    fun buriedCardPersistsForCurrentStudyDayAndExpiresTheNextDay() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val today = 10L * MillisPerDay + 1_000L

        val buried = store.buryCard(42L, today)

        assertEquals(setOf(42L), buried.buriedCardIds)
        assertEquals(
            setOf(42L),
            PersistentCollectionStore(database).loadLocalReviews(today + 1_000L).buriedCardIds,
        )
        assertTrue(store.loadLocalReviews(today + MillisPerDay).buriedCardIds.isEmpty())
        driver.close()
    }

    @Test
    fun buriedNotePersistsForCurrentStudyDayAndCoversEverySibling() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val today = 10L * MillisPerDay + 1_000L
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf("siblings" to SyncNote("siblings", fields = listOf("front", "back"))),
                cards = mapOf(
                    41L to SyncCard(41L, "siblings", "One", ord = 0),
                    42L to SyncCard(42L, "siblings", "Two", ord = 1),
                ),
                deckNames = setOf("One", "Two"),
            ),
            nowMillis = today,
        )

        val buried = store.buryNote("siblings", today)

        assertEquals(setOf("siblings"), buried.buriedNoteGuids)
        assertEquals(
            setOf("siblings"),
            PersistentCollectionStore(database).loadLocalReviews(today + 1_000L).buriedNoteGuids,
        )
        val displayed = store.load(nowMillis = today).collection.withLocalContent(store.loadLocalContent())
        assertTrue(
            displayed.asDecks(
                nowMillis = today,
                buriedNoteGuids = buried.buriedNoteGuids,
            ).all { it.cards.isEmpty() },
        )
        assertTrue(store.loadLocalReviews(today + MillisPerDay).buriedNoteGuids.isEmpty())
        driver.close()
    }

    @Test
    fun noteMarkCopyAndDownloadedDeleteUseTheNormalSyncOutbox() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val source = SyncNote(
            guid = "source-note",
            notetypeId = NotetypeCatalog.BasicId,
            fields = listOf("front", "back", "extra"),
            tags = listOf("source"),
            checksum = "source-checksum",
        )
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf(source.guid to source),
                cards = mapOf(
                    51L to SyncCard(51L, source.guid, "One", ord = 0),
                    52L to SyncCard(52L, source.guid, "Two", ord = 1),
                ),
                notetypes = NotetypeCatalog.definitions,
                deckNames = setOf("One", "Two"),
            ),
            nowMillis = 1_000L,
        )

        val marked = store.setNoteMarked(source.guid, true, nowMillis = 2_000L)
        assertEquals(listOf("source", "marked"), marked.overrides.getValue(source.guid).tags)
        assertTrue(store.prepareSyncUpload().notes.single { it.guid == source.guid }.body!!.tags.contains("marked"))
        store.setNoteMarked(source.guid, false, nowMillis = 3_000L)
        assertFalse(store.prepareSyncUpload().notes.single { it.guid == source.guid }.body!!.tags.contains("marked"))

        val copy = store.createNoteCopy(
            source.guid,
            nowMillis = 4_000L,
            copyGuid = "local-source-copy",
        )
        val copiedNote = copy.content.notes.getValue(copy.noteGuid)
        assertEquals(source.fields, copiedNote.fields)
        assertEquals(source.tags, copiedNote.tags)
        val copiedCards = copy.content.cards.values.filter { it.noteGuid == copy.noteGuid }
        assertEquals(setOf(0, 1), copiedCards.map { it.ord }.toSet())
        assertEquals(setOf("One", "Two"), copiedCards.map { it.deckName }.toSet())
        assertNotNull(store.prepareSyncUpload().notes.single { it.guid == copy.noteGuid }.body)
        store.recordReview(
            store.load(nowMillis = 4_500L).collection.cards.getValue(51L),
            Rating.Good,
            reviewedAtMillis = 4_500L,
        )

        val deleted = store.deleteLocalNote(source.guid, nowMillis = 5_000L)
        assertTrue(source.guid in deleted.deletedNoteGuids)
        assertFalse(source.guid in store.load(nowMillis = 5_000L).collection.withLocalContent(deleted).notes)
        val plan = store.prepareSyncUpload()
        assertTrue(plan.reviews.any { it.noteGuid == source.guid })
        val deletion = plan.notes.single { it.guid == source.guid }
        assertEquals("delete", deletion.operation)
        assertEquals(setOf(51L, 52L), deletion.deleteRequest!!.cards.toSet())
        driver.close()
    }

    @Test
    fun resetCardRemainsNewAfterProjectionRebuildAndKeepsReviewHistory() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val added = store.addLocalNote(
            AddNoteDraft("Reset", "front", "back"),
            nowMillis = 1_000L,
            noteGuid = "reset-card",
        )
        val card = added.content.cards.values.single()
        store.recordReview(card, Rating.Good, reviewedAtMillis = 2_000L)
        assertNotNull(store.loadLocalReviews(2_000L).schedules[card.cardId])
        store.setCardDueDate(card.cardId, 10L * MillisPerDay, nowMillis = 2_500L)

        val reset = store.resetCard(card.cardId, nowMillis = 3_000L)

        assertNull(reset.schedules[card.cardId])
        assertTrue(reset.dueDateOverrides.isEmpty())
        assertEquals(1, reset.reviewedToday)
        val resetUpload = store.prepareSyncUpload().cardScheduleResets.single()
        assertEquals(card.cardId, resetUpload.cardId)
        assertEquals(3_000L, resetUpload.body.scheduleResetThroughReviewId)
        val reopened = PersistentCollectionStore(database)
        assertNull(reopened.load(nowMillis = 3_001L).localReviews.schedules[card.cardId])
        val reviewedAgain = reopened.recordReview(card, Rating.Good, reviewedAtMillis = 4_000L)
        assertEquals(1, reviewedAgain.schedule.repetitions)
        driver.close()
    }

    @Test
    fun manualDueDateSurvivesProjectionRebuildWithoutChangingFsrsStateAndClearsOnReview() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val added = store.addLocalNote(
            AddNoteDraft("Due", "front", "back"),
            nowMillis = 1_000L,
            noteGuid = "due-card",
        )
        val card = added.content.cards.values.single()
        val reviewed = store.recordReview(card, Rating.Good, reviewedAtMillis = 2_000L)
        val originalSchedule = reviewed.schedule
        val dueAt = 10L * MillisPerDay

        val changed = store.setCardDueDate(card.cardId, dueAt, nowMillis = 3_000L)

        assertEquals(dueAt, changed.dueDateOverrides[card.cardId])
        assertEquals(originalSchedule, changed.schedules[card.cardId])
        val dueUpload = store.prepareSyncUpload().cardDueDates.single()
        assertEquals(card.cardId, dueUpload.cardId)
        assertEquals(dueAt, dueUpload.body.dueDateOverrideMillis)
        val restored = PersistentCollectionStore(database).load(nowMillis = 3_001L).localReviews
        assertEquals(dueAt, restored.dueDateOverrides[card.cardId])
        assertEquals(originalSchedule, restored.schedules[card.cardId])
        assertTrue(
            SyncedCollection().withLocalContent(store.loadLocalContent()).asDecks(
                localSchedules = restored.schedules,
                nowMillis = 3_001L,
                dueDateOverrides = restored.dueDateOverrides,
            ).single().cards.isEmpty(),
        )

        val reviewedAgain = store.recordReview(card, Rating.Good, reviewedAtMillis = 4_000L)
        assertTrue(reviewedAgain.snapshot.dueDateOverrides.isEmpty())
        assertEquals(0L, store.prepareSyncUpload().cardDueDates.single().body.dueDateOverrideMillis)
        driver.close()
    }

    @Test
    fun newerDownloadedDueDateWinsAndClearsTheLocalOutbox() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val note = SyncNote("remote-due", fields = listOf("front", "back"))
        fun collection(cardId: Long, dueAt: Long, modifiedAt: Long) = SyncedCollection(
            notes = mapOf(note.guid to note),
            cards = mapOf(
                cardId to SyncCard(
                    cardId = cardId,
                    noteGuid = note.guid,
                    deckName = "Due",
                    dueDateOverrideMillis = dueAt,
                    dueDateOverrideModifiedAt = epochMillisToRfc3339(modifiedAt),
                    dueDateOverrideClientModifiedAt = epochMillisToRfc3339(modifiedAt),
                ),
            ),
            deckNames = setOf("Due"),
        )
        val firstDue = 10L * MillisPerDay
        val localDue = 11L * MillisPerDay
        val remoteDue = 12L * MillisPerDay
        store.replaceCollection(collection(42L, firstDue, 1_000L), nowMillis = 1_001L)
        assertEquals(firstDue, store.loadLocalReviews(1_001L).dueDateOverrides[42L])
        store.setCardDueDate(42L, localDue, nowMillis = 2_000L)
        assertEquals(localDue, store.loadLocalReviews(2_001L).dueDateOverrides[42L])

        store.replaceCollection(collection(43L, firstDue, 1_500L), nowMillis = 2_001L)
        assertEquals(localDue, store.loadLocalReviews(2_001L).dueDateOverrides[43L])
        store.replaceCollection(collection(43L, remoteDue, 3_000L), nowMillis = 3_001L)

        assertEquals(remoteDue, store.loadLocalReviews(3_001L).dueDateOverrides[43L])
        assertTrue(store.prepareSyncUpload().cardDueDates.isEmpty())
        driver.close()
    }

    @Test
    fun emptyLocalDeckPersistsAcrossRestartAndPullReplacement() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)

        val created = store.createLocalDeck(" Languages :: Verbs ", nowMillis = 1_000L)
        assertEquals(setOf("Languages", "Languages::Verbs"), created.deckNames)
        assertEquals(
            listOf("Languages", "Languages::Verbs"),
            SyncedCollection().withLocalContent(created).asDecks().map(DeckSummary::name),
        )

        store.replaceCollection(SyncedCollection(deckNames = setOf("Downloaded")))
        assertEquals(
            setOf("Languages", "Languages::Verbs"),
            PersistentCollectionStore(database).loadLocalContent().deckNames,
        )
        assertFailsWith<IllegalArgumentException> { store.createLocalDeck("languages::verbs") }
        driver.close()
    }

    @Test
    fun localDeckRenameAndDeleteUpdateCardsReviewsAndHierarchyTransactionally() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        store.createLocalDeck("Languages::Verbs", nowMillis = 1_000L)
        store.saveDeckOptions("Languages::Verbs", DeckOptions(newCardsPerDay = 7), nowMillis = 1_500L)
        val added = store.addLocalNote(
            AddNoteDraft("Languages::Verbs", "laufen", "to run"),
            nowMillis = 2_000L,
            noteGuid = "local-deck-actions",
        )
        val card = added.content.cards.values.single()
        store.recordReview(card, Rating.Good, reviewedAtMillis = 3_000L)

        val renamed = store.renameLocalDeck("Languages", "Study", nowMillis = 4_000L)

        assertEquals(setOf("Study", "Study::Verbs"), renamed.deckNames)
        assertEquals("Study::Verbs", renamed.cards.getValue(card.cardId).deckName)
        assertEquals(7, renamed.deckOptions.getValue("Study::Verbs").newCardsPerDay)
        assertNotNull(store.loadLocalReviews(3_000L).schedules[card.cardId])
        assertEquals("Study::Verbs", store.loadLocalReviews(3_000L).lastReviewDeck)
        assertEquals(
            setOf("Study", "Study::Verbs"),
            PersistentCollectionStore(database).loadLocalContent().deckNames,
        )

        val deleted = store.deleteLocalDeck("Study")

        assertTrue(deleted.deckNames.isEmpty())
        assertTrue(deleted.cards.isEmpty())
        assertTrue(deleted.notes.isEmpty())
        assertTrue(deleted.deckOptions.isEmpty())
        assertTrue(store.loadLocalReviews(3_000L).schedules.isEmpty())
        assertNull(store.loadLocalReviews(3_000L).lastReviewDeck)
        driver.close()
    }

    @Test
    fun downloadedDeckRenameAndDeleteUsePersistentLocalOverlays() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        store.createLocalDeck("First")
        store.createLocalDeck("Second")
        val downloaded = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(
                42L to SyncCard(42, "note", "Downloaded"),
                43L to SyncCard(43, "note", "Downloaded::Child", ord = 1),
            ),
            deckNames = setOf("Downloaded", "Downloaded::Child"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            downloaded,
        )
        store.saveDeckOptions("Downloaded", DeckOptions(newCardsPerDay = 9), nowMillis = 400L)
        store.recordReview(downloaded.cards.getValue(42L), Rating.Good, reviewedAtMillis = 500L)

        assertFailsWith<IllegalArgumentException> { store.renameLocalDeck("First", "second") }
        assertFailsWith<IllegalArgumentException> { store.renameLocalDeck("Downloaded", "Second") }
        val renamed = store.renameLocalDeck("Downloaded", "Changed", nowMillis = 1_000L)
        assertEquals(mapOf("Downloaded" to "Changed"), renamed.deckOverrides)
        assertEquals(2, aggregatePendingDeckChanges(renamed.pendingSyncByDeck).getValue("Changed").changed)
        assertEquals(9, renamed.deckOptions.getValue("Changed").newCardsPerDay)
        val displayedCard = downloaded.withLocalContent(renamed).cards.getValue(42L)
        assertEquals("Changed", displayedCard.deckName)
        assertEquals("Changed::Child", downloaded.withLocalContent(renamed).cards.getValue(43L).deckName)
        assertEquals("Downloaded", store.load().collection.cards.getValue(42L).deckName)
        assertNotNull(store.loadLocalReviews(1_500L).schedules[42L])
        assertEquals("Changed", store.loadLocalReviews(1_500L).lastReviewDeck)

        store.replaceCollection(downloaded.copy(serverTime = "later"))
        val reopened = PersistentCollectionStore(database).loadLocalContent()
        assertEquals("Changed", downloaded.withLocalContent(reopened).cards.getValue(42L).deckName)

        val deleted = store.deleteLocalDeck("Changed", nowMillis = 2_000L)
        assertTrue(downloaded.withLocalContent(deleted).cards.isEmpty())
        assertTrue(store.loadLocalReviews(2_000L).schedules.isEmpty())
        assertEquals(
            mapOf("Downloaded" to null, "Downloaded::Child" to null),
            deleted.deckOverrides,
        )
        assertEquals(setOf("First", "Second"), deleted.deckNames)
        driver.close()
    }

    @Test
    fun deckOptionsPersistAcrossRestartAndPullButClearAcrossAccounts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val custom = DeckOptions(
            newCardsPerDay = 12,
            autoplayAudio = false,
            desiredRetention = 0.93,
            maximumIntervalDays = 500,
            newCardGatherOrder = NewCardGatherOrder.HighestPosition,
            newCardSortOrder = NewCardSortOrder.RandomCard,
            newReviewMixOrder = QueueMixOrder.BeforeReviews,
            interdayLearningMixOrder = QueueMixOrder.AfterReviews,
            reviewSortOrder = ReviewSortOrder.DifficultyDescending,
        )
        store.saveDeckOptions("Downloaded", custom, nowMillis = 1_000L)
        store.replaceCollection(SyncedCollection(deckNames = setOf("Downloaded")))

        assertEquals(
            custom.validated(),
            PersistentCollectionStore(database).loadLocalContent().deckOptions["Downloaded"],
        )
        store.saveSignedInState(
            StoredSyncAuth("a", "a", DefaultKelmaSyncEndpoint, "account-a"),
            SyncedCollection(deckNames = setOf("Downloaded")),
        )
        assertEquals(custom.validated(), store.loadLocalContent().deckOptions["Downloaded"])
        store.renameLocalDeck("Downloaded", "Changed")
        assertEquals(mapOf("Downloaded" to "Changed"), store.loadLocalContent().deckOverrides)
        store.saveSignedInState(
            StoredSyncAuth("b", "b", DefaultKelmaSyncEndpoint, "account-b"),
            SyncedCollection(deckNames = setOf("Downloaded")),
        )
        assertTrue(store.loadLocalContent().deckOptions.isEmpty())
        assertTrue(store.loadLocalContent().deckOverrides.isEmpty())
        driver.close()
    }

    @Test
    fun noteAndReviewOutboxesUploadIdempotentlyAndReconcileAfterPull() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val downloaded = SyncedCollection(
            notes = mapOf("server-note" to SyncNote("server-note", fields = listOf("one", "two"))),
            cards = mapOf(7L to SyncCard(7, "server-note", "Server", scheduling = newCardScheduling())),
            deckNames = setOf("Server"),
        )
        store.replaceCollection(downloaded)
        store.recordReview(downloaded.cards.getValue(7L), Rating.Good, reviewedAtMillis = 10_000L)
        val added = store.addLocalNote(
            AddNoteDraft("Authored", "front", "back"),
            nowMillis = 11_000L,
            noteGuid = "upload-note",
        )
        assertEquals(1, store.loadLocalReviews().pendingSyncByDeck.getValue("Server").changed)
        assertEquals(1, added.content.pendingSyncByDeck.getValue("Authored").added)

        val firstPlan = store.prepareSyncUpload()
        assertEquals(1, firstPlan.reviews.size)
        assertEquals(10_000L, firstPlan.reviews.single().reviewId)
        assertEquals("server-note", firstPlan.reviews.single().noteGuid)
        assertEquals("upload-note", firstPlan.notes.single().guid)
        assertNotNull(firstPlan.notes.single().deck)
        assertNotNull(firstPlan.notes.single().notetype)
        assertEquals(listOf(added.cardId), firstPlan.notes.single().cards.map { it.first })
        assertEquals(firstPlan, PersistentCollectionStore(database).prepareSyncUpload())

        store.applySyncPushResult(
            SyncPushResult(
                uploadedReviewIds = setOf(10_000L),
                uploadedNoteGuids = setOf("upload-note"),
            ),
        )
        assertFalse(store.loadLocalReviews(12_000L).canUndo)
        val serverAfterPush = downloaded.copy(
            notes = downloaded.notes + ("upload-note" to SyncNote(
                "upload-note",
                notetypeId = NotetypeCatalog.BasicId,
                fields = listOf("front", "back"),
                checksum = "uploaded-note-checksum",
            )),
            cards = downloaded.cards + (added.cardId to SyncCard(
                added.cardId,
                "upload-note",
                "Authored",
                scheduling = newCardScheduling(),
            )),
            reviews = mapOf(
                10_000L to SyncReview(10_000L, 7, "server-note", deckName = "Server", ease = 3),
            ),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("Server", "Authored"),
        )
        store.replaceCollection(serverAfterPush, nowMillis = 12_000L)

        assertTrue(store.prepareSyncUpload().isEmpty)
        assertTrue(store.loadLocalContent().cards.isEmpty())
        assertTrue(store.loadLocalContent().pendingSyncByDeck.isEmpty())
        assertTrue(store.loadLocalReviews(12_000L).pendingSyncByDeck.isEmpty())
        assertNotNull(store.loadLocalReviews(12_000L).schedules[7L])
        store.updateNoteFields("upload-note", listOf("edited", "back"), emptyList(), nowMillis = 13_000L)
        assertEquals(1, store.loadLocalContent().pendingSyncByDeck.getValue("Authored").changed)
        val edit = store.prepareSyncUpload().notes.single()
        assertEquals("uploaded-note-checksum", edit.body?.baseChecksum)
        assertEquals(listOf("edited", "back"), edit.body?.fields)
        store.applySyncPushResult(
            SyncPushResult(
                conflicts = listOf(SyncUploadConflict("note", "upload-note", "{\"server\":true}")),
            ),
        )
        val conflict = PersistentCollectionStore(database).loadSyncConflicts().single()
        assertEquals("upload-note", conflict.resourceKey)
        assertTrue(store.prepareSyncUpload().notes.isEmpty())
        store.resolveSyncConflict(conflict, keepLocal = true)
        assertTrue(store.prepareSyncUpload().notes.single().forceOverride)
        store.applySyncPushResult(SyncPushResult(conflicts = listOf(conflict)))
        store.resolveSyncConflict(conflict, keepLocal = false)
        assertTrue(store.loadSyncConflicts().isEmpty())
        assertTrue(store.loadLocalContent().overrides.isEmpty())
        assertTrue(store.loadLocalContent().pendingSyncByDeck.isEmpty())
        store.deleteLocalNote("upload-note")
        val delete = store.prepareSyncUpload().notes.single()
        assertEquals("delete", delete.operation)
        assertEquals(setOf("upload-note"), delete.deleteRequest?.notes?.toSet())
        assertEquals(setOf(added.cardId), delete.deleteRequest?.cards?.toSet())
        driver.close()
    }

    @Test
    fun downloadedDeckRenameAndDeleteProduceServerMutationPlans() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val raw = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(9L to SyncCard(9, "note", "Old::Child")),
            studyDays = mapOf(
                "1\u0000Old::Child" to SyncStudyDay(1, "Old::Child", newStudied = 2),
            ),
            deckRecords = mapOf("Old" to SyncDeck("Old"), "Old::Child" to SyncDeck("Old::Child")),
            deckNames = setOf("Old", "Old::Child"),
            serverTime = "2027-01-15T08:00:00.000Z",
        )
        store.replaceCollection(raw)

        store.renameLocalDeck("Old", "New", nowMillis = 20_000L)
        val rename = store.prepareSyncUpload().decks.single()
        assertEquals("rename", rename.operation)
        assertEquals("Old", rename.sourceName)
        assertEquals("New", rename.targetName)
        assertEquals("New::Child", rename.cards.single().second.deckName)
        assertEquals("2027-01-15T08:00:00.000Z", rename.cards.single().second.clientModifiedAt)
        assertEquals("2027-01-15T08:00:00.000Z", rename.targetBody?.clientModifiedAt)
        assertEquals(listOf("New::Child"), rename.additionalDecks.map { it.first })
        assertEquals(setOf("Old", "Old::Child"), rename.deleteRequest?.decks?.toSet())
        val conflict = SyncUploadConflict("deck", "Old", "{\"server\":true}")
        store.applySyncPushResult(SyncPushResult(conflicts = listOf(conflict)))
        assertEquals("Old", store.loadSyncConflicts().single().resourceKey)
        store.resolveSyncConflict(conflict, keepLocal = true)
        assertTrue(store.prepareSyncUpload().decks.single().forceOverride)

        store.applySyncPushResult(SyncPushResult(uploadedDeckSources = setOf("Old")))
        val renamedRaw = raw.copy(
            cards = mapOf(9L to raw.cards.getValue(9L).copy(deckName = "New::Child")),
            deckRecords = mapOf("New" to SyncDeck("New"), "New::Child" to SyncDeck("New::Child")),
            deckNames = setOf("New", "New::Child"),
        )
        store.replaceCollection(renamedRaw)
        assertTrue(store.loadLocalContent().deckOverrides.isEmpty())
        assertTrue(store.prepareSyncUpload().isEmpty)

        store.recordReview(renamedRaw.cards.getValue(9L), Rating.Good, reviewedAtMillis = 25_000L)
        store.deleteLocalDeck("New", nowMillis = 30_000L)
        val deletionPlan = store.prepareSyncUpload()
        val delete = deletionPlan.decks.single()
        assertEquals("delete", delete.operation)
        assertEquals(setOf(9L), delete.deleteRequest?.cards?.toSet())
        assertEquals(setOf("note"), delete.deleteRequest?.notes?.toSet())
        assertEquals(setOf("New", "New::Child"), delete.deleteRequest?.decks?.toSet())
        assertEquals(1, deletionPlan.reviews.size)
        assertNull(store.loadLocalReviews(30_000L).schedules[9L])

        val deleteConflict = SyncUploadConflict("deck", "New", "{\"server\":true}")
        store.applySyncPushResult(SyncPushResult(conflicts = listOf(deleteConflict)))
        store.resolveSyncConflict(deleteConflict, keepLocal = false, nowMillis = 31_000L)
        assertNotNull(store.loadLocalReviews(31_000L).schedules[9L])
        assertEquals(1, store.prepareSyncUpload().reviews.size)
        driver.close()
    }

    @Test
    fun deckOptionsRemainLocalAndIgnoreLegacySyncedCopies() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val serverOptions = DeckOptions(newCardsPerDay = 6, desiredRetention = 0.92)
        store.replaceCollection(
            SyncedCollection(
                deckRecords = mapOf(
                    "Deck" to SyncDeck(
                        "Deck",
                        config = buildJsonObject {
                            put("kelma_options", Json.encodeToJsonElement(serverOptions))
                        },
                        checksum = "server-deck-checksum",
                    ),
                ),
                deckNames = setOf("Deck"),
            ),
        )
        assertNull(store.loadLocalContent().deckOptions["Deck"])

        val localOptions = serverOptions.copy(
            newCardsPerDay = 12,
            newCardGatherOrder = NewCardGatherOrder.RandomCards,
            newCardSortOrder = NewCardSortOrder.RandomCard,
            newReviewMixOrder = QueueMixOrder.BeforeReviews,
            interdayLearningMixOrder = QueueMixOrder.AfterReviews,
            reviewSortOrder = ReviewSortOrder.RetrievabilityAscending,
        )
        store.saveDeckOptions("Deck", localOptions, nowMillis = 1_000L)
        assertEquals(localOptions.validated(), store.loadLocalContent().deckOptions["Deck"])
        assertTrue(store.prepareSyncUpload().isEmpty)
        store.replaceCollection(
            SyncedCollection(
                deckRecords = mapOf("Deck" to SyncDeck("Deck", checksum = "new-server-checksum")),
                deckNames = setOf("Deck"),
            ),
        )
        assertEquals(localOptions.validated(), PersistentCollectionStore(database)
            .loadLocalContent().deckOptions["Deck"])
        assertTrue(store.prepareSyncUpload().isEmpty)

        database.kelmaQueries.upsertLocalDeckSync(
            "Deck", "upsert", null, "legacy-options-checksum", 500L,
        )
        val legacyUpload = store.prepareSyncUpload().decks.single()
        assertTrue("kelma_options" !in legacyUpload.targetBody!!.config)
        database.kelmaQueries.deleteLocalDeckSync("Deck")

        store.createLocalDeck("Local")
        store.saveDeckOptions("Local", localOptions)
        val localDeckUpload = store.prepareSyncUpload().decks.single()
        assertTrue("kelma_options" !in localDeckUpload.targetBody!!.config)
        driver.close()
    }

    @Test
    fun serverCardSchedulingNeverReplacesTheLocalProjection() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val card = SyncCard(55, "note", "Deck", scheduling = newCardScheduling())
        val initial = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(55L to card),
            deckNames = setOf("Deck"),
        )
        store.replaceCollection(initial)
        val localSchedule = store.recordReview(card, Rating.Good, reviewedAtMillis = 1_000L).schedule
        val reviewId = store.prepareSyncUpload().reviews.single().reviewId
        val newerCard = card.copy(
            scheduling = buildJsonObject { put("queue", 2); put("ivl", 30); put("reps", 2) },
            clientModifiedAt = "1970-01-01T00:00:02.000Z",
        )

        store.replaceCollection(initial.copy(cards = mapOf(55L to newerCard)), nowMillis = 2_000L)
        assertEquals(localSchedule, store.loadLocalReviews(2_000L).schedules[55L])

        store.applySyncPushResult(SyncPushResult(uploadedReviewIds = setOf(reviewId)))
        store.replaceCollection(
            initial.copy(
                cards = mapOf(55L to newerCard),
                reviews = mapOf(
                    reviewId to SyncReview(reviewId, 55, "note", deckName = "Deck", ease = 3, interval = 30),
                ),
            ),
            nowMillis = 2_000L,
        )
        assertEquals(localSchedule, store.loadLocalReviews(2_000L).schedules[55L])

        database.kelmaQueries.upsertLocalSchedule(
            55L, ReviewPhase.Review.name, 999_999L, 999.0, 9.0, 999L, 999L, 99L, 999L, null,
        )
        val reopened = PersistentCollectionStore(database).load(2_000L)
        assertEquals(localSchedule, reopened.localReviews.schedules[55L])
        driver.close()
    }

    @Test
    fun suspendedStudyStateUsesAnIndependentDurableOutbox() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val card = SyncCard(55, "note", "Deck")
        val initial = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            deckNames = setOf("Deck"),
        )
        store.replaceCollection(initial)

        val local = store.setCardsStudyState(listOf(card), CardStudyState.Suspended, nowMillis = 1_000L)
        val pending = store.prepareSyncUpload().cardStudyStates.single()

        assertEquals(CardStudyState.Suspended, local.cardStudyStates[cardStudyKey("note", 0)])
        assertEquals(CardStudyState.Suspended, pending.body.studyState)
        assertEquals("1970-01-01T00:00:01.000Z", pending.body.studyStateClientModifiedAt)

        store.applySyncPushResult(SyncPushResult(uploadedCardStudyKeys = setOf(pending.key)))
        store.replaceCollection(
            initial.copy(
                cards = mapOf(
                    card.cardId to card.copy(
                        studyState = CardStudyState.Suspended,
                        studyStateModifiedAt = "1970-01-01T00:00:02.000Z",
                        studyStateClientModifiedAt = "1970-01-01T00:00:01.000Z",
                    ),
                ),
            ),
        )
        assertTrue(store.loadLocalContent().cardStudyStates.isEmpty())
        driver.close()
    }

    @Test
    fun legacyPendingReviewsBackfillPortableIdentityBeforeReplayAndUpload() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val reviewedAt = 1_700_000_000_000L
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf("portable" to SyncNote("portable", fields = listOf("front", "back"))),
                cards = mapOf(88L to SyncCard(88, "portable", "Deck")),
                deckNames = setOf("Deck"),
            ),
        )
        database.kelmaQueries.insertLocalReviewEvent(
            88L,
            "",
            0L,
            "Deck",
            Rating.Good.name,
            reviewedAt,
            epochDayAt(reviewedAt),
            0L,
            null,
            """{"cardId":88,"phase":"Learning","dueAtMillis":1700000600000,"stability":3.0,""" +
                """"difficulty":5.0,"scheduledDays":0,"repetitions":1,"lapses":0,""" +
                """"lastReviewAtMillis":1700000000000}""",
            1L,
            0L,
            reviewedAt,
        )

        val restored = store.load(reviewedAt)
        assertNotNull(restored.localReviews.schedules[88L])
        val upload = store.prepareSyncUpload().reviews.single()
        assertEquals("portable", upload.noteGuid)
        assertEquals(0, upload.cardOrd)
        driver.close()
    }

    @Test
    fun pulledReviewHistoryRebuildsWithLocalOptionsAndIgnoresOriginIntervals() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val reviewedAt = 1_700_000_000_000L
        val card = SyncCard(
            77,
            "portable-note",
            "Deck",
            scheduling = buildJsonObject {
                put("queue", 2)
                put("due_at_ms", reviewedAt + 999 * MillisPerDay)
                put("ivl", 999)
                put("stability", 999.0)
            },
        )
        val localOptions = DeckOptions(learningStepsMinutes = listOf(2, 20))
        store.saveDeckOptions("Deck", localOptions)
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf("portable-note" to SyncNote("portable-note", fields = listOf("front", "back"))),
                cards = mapOf(77L to card),
                reviews = mapOf(
                    reviewedAt to SyncReview(
                        reviewId = reviewedAt,
                        sourceCardId = 999_999,
                        noteGuid = "portable-note",
                        cardOrd = 0,
                        deckName = "Deck",
                        ease = 3,
                        interval = 999,
                        factor = 1_300,
                    ),
                ),
                studyDays = mapOf(
                    "${epochDayAt(reviewedAt)}\u0000Deck" to SyncStudyDay(
                        epochDayAt(reviewedAt),
                        "Deck",
                        newStudied = 500,
                        reviewStudied = 500,
                    ),
                ),
                deckNames = setOf("Deck"),
            ),
            nowMillis = reviewedAt,
        )

        val firstProjection = store.loadLocalReviews(reviewedAt)
        assertEquals(ReviewPhase.Learning, firstProjection.schedules.getValue(77).phase)
        assertEquals(reviewedAt + 20 * 60_000L, firstProjection.schedules.getValue(77).dueAtMillis)
        assertEquals(1, firstProjection.reviewedToday)
        assertEquals(
            DeckStudyCounts(newCards = 500, reviews = 500),
            firstProjection.studiedTodayByDeck["Deck"],
        )

        store.saveDeckOptions("Deck", localOptions.copy(learningStepsMinutes = listOf(3, 25)))
        assertEquals(
            reviewedAt + 25 * 60_000L,
            store.loadLocalReviews(reviewedAt).schedules.getValue(77).dueAtMillis,
        )
        assertTrue(store.prepareSyncUpload().isEmpty)
        driver.close()
    }

    @Test
    fun pulledHistoryUsesTheInjectedLocalScheduler() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        var receivedScheduling = buildJsonObject { put("unexpected", true) }
        val localScheduler = object : SchedulingEngine {
            override fun review(
                card: SyncCard,
                previous: LocalCardSchedule?,
                rating: Rating,
                reviewedAtMillis: Long,
                serverLastReviewAtMillis: Long?,
                options: DeckOptions,
            ): LocalCardSchedule {
                receivedScheduling = card.scheduling
                return LocalCardSchedule(
                    card.cardId,
                    ReviewPhase.Review,
                    reviewedAtMillis + 1_234L,
                    stability = 42.0,
                    difficulty = 4.0,
                    scheduledDays = 1,
                    repetitions = (previous?.repetitions ?: 0) + 1,
                    lapses = previous?.lapses ?: 0,
                    lastReviewAtMillis = reviewedAtMillis,
                )
            }
        }
        val store = PersistentCollectionStore(KelmaDatabase(driver), scheduler = localScheduler)
        store.observeCloudStudyDayPolicy(
            AccountStudyDayPolicy(version = 1, timezoneId = "UTC", dayStartHour = 0),
        )
        val reviewedAt = 1_700_000_000_000L
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
                cards = mapOf(
                    1L to SyncCard(
                        1,
                        "note",
                        "Deck",
                        scheduling = buildJsonObject { put("queue", 2); put("ivl", 500) },
                    ),
                ),
                reviews = mapOf(
                    reviewedAt to SyncReview(reviewedAt, 1, "note", ease = 4, interval = 500),
                ),
                deckNames = setOf("Deck"),
            ),
            nowMillis = reviewedAt,
        )

        val projection = store.loadLocalReviews(reviewedAt).schedules.getValue(1)
        assertEquals(1_700_006_400_000L, projection.dueAtMillis)
        assertTrue(receivedScheduling.isEmpty())
        driver.close()
    }

    @Test
    fun authoredCardUploadsAContentSeedBeforeItsReviewBecomesPortable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val added = store.addLocalNote(
            AddNoteDraft("Deck", "front", "back"),
            nowMillis = 1_000L,
            noteGuid = "authored-local-schedule",
        )
        val card = added.content.cards.getValue(added.cardId)
        store.recordReview(card, Rating.Easy, reviewedAtMillis = 2_000L)

        val contentPlan = store.prepareSyncUpload()
        assertTrue(contentPlan.reviews.isEmpty())
        val uploadedCard = contentPlan.notes.single().cards.single().second
        assertTrue(uploadedCard.scheduling.isEmpty())

        store.applySyncPushResult(SyncPushResult(uploadedNoteGuids = setOf("authored-local-schedule")))
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf(
                    "authored-local-schedule" to SyncNote(
                        "authored-local-schedule",
                        fields = listOf("front", "back"),
                    ),
                ),
                cards = mapOf(added.cardId to card),
                deckNames = setOf("Deck"),
            ),
            nowMillis = 3_000L,
        )
        assertEquals(1, store.prepareSyncUpload().reviews.size)
        assertNotNull(store.loadLocalReviews(3_000L).schedules[added.cardId])
        driver.close()
    }

    @Test
    fun deckMaximumAnswerTimeCapsPersistedReviewDuration() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        store.saveDeckOptions("Timed", DeckOptions(maximumAnswerSeconds = 3))
        val card = store.addLocalNote(
            AddNoteDraft("Timed", "front", "back"),
            noteGuid = "timed-note",
        ).content.cards.values.single()

        store.recordReview(card, Rating.Good, reviewedAtMillis = 10_000L, durationMillis = 20_000L)

        assertEquals(3_000L, database.kelmaQueries.selectLatestLocalReviewDuration().executeAsOne())
        driver.close()
    }

    @Test
    fun failedLocalCardInsertRollsBackItsNote() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_local_card
                BEFORE INSERT ON local_cards
                BEGIN SELECT RAISE(ABORT, 'forced card failure'); END
            """.trimIndent(),
            parameters = 0,
        )

        assertFailsWith<Exception> {
            store.addLocalNote(
                AddNoteDraft("Deck", "front", "back"),
                noteGuid = "local-rollback-test",
            )
        }
        assertTrue(store.loadLocalContent().notes.isEmpty())
        assertTrue(store.loadLocalContent().cards.isEmpty())
        assertTrue(store.loadLocalContent().deckNames.isEmpty())
        driver.close()
    }

    @Test
    fun updatingALocalNoteChangesFieldsTagsAndDeckButKeepsSchedules() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val added = store.addLocalNote(
            AddNoteDraft("French", "bonjour", "hello", listOf("french")),
            noteGuid = "local-update",
        )
        val card = added.content.cards.values.single()
        store.recordReview(card, Rating.Good, 1_000_000L)

        val updated = store.updateLocalNote(
            "local-update",
            AddNoteDraft("Spanish", "hola", "hello", listOf("spanish", "greeting")),
        )

        assertEquals(listOf("hola", "hello"), updated.notes.getValue("local-update").fields)
        assertEquals(listOf("spanish", "greeting"), updated.notes.getValue("local-update").tags)
        assertEquals("Spanish", updated.cards.getValue(card.cardId).deckName)
        assertNotNull(store.loadLocalReviews(1_000_000L).schedules[card.cardId])
        driver.close()
    }

    @Test
    fun downloadedNoteEditsPersistAsOverridesAcrossPullReplacement() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val server = SyncedCollection(
            notes = mapOf(
                "server-note" to SyncNote(
                    "server-note",
                    NotetypeCatalog.BasicId,
                    listOf("server front", "server back"),
                ),
            ),
            cards = mapOf(1L to SyncCard(1L, "server-note", "Deck")),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("Deck"),
            serverTime = "first",
        )
        store.replaceCollection(server)

        val local = store.updateNoteFields(
            "server-note",
            listOf("<b>edited front</b>", "edited back"),
            listOf("edited"),
            1_000_000L,
        )
        assertEquals(1, local.overrides.size)
        assertEquals(
            listOf("<b>edited front</b>", "edited back"),
            server.withLocalContent(local).notes.getValue("server-note").fields,
        )

        store.replaceCollection(
            server.copy(
                notes = mapOf(
                    "server-note" to SyncNote(
                        "server-note",
                        NotetypeCatalog.BasicId,
                        listOf("refreshed front", "refreshed back"),
                    ),
                ),
                serverTime = "second",
            ),
        )
        val restarted = PersistentCollectionStore(KelmaDatabase(driver)).load()
        assertEquals(
            listOf("<b>edited front</b>", "edited back"),
            restarted.collection.withLocalContent(restarted.localContent)
                .notes.getValue("server-note").fields,
        )
        driver.close()
    }

    @Test
    fun deletingALocalNoteRemovesCardsSchedulesAndReviewEvents() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val added = store.addLocalNote(
            AddNoteDraft("French", "bonjour", "hello"),
            noteGuid = "local-delete",
        )
        val card = added.content.cards.values.single()
        store.recordReview(card, Rating.Again, 1_000_000L)
        assertEquals(1, store.loadLocalReviews(1_000_000L).reviewedToday)

        val after = store.deleteLocalNote("local-delete")

        assertEquals(0, after.cardCount)
        assertTrue(after.notes.isEmpty())
        val reviews = store.loadLocalReviews(1_000_000L)
        assertTrue(reviews.schedules.isEmpty())
        assertEquals(0, reviews.reviewedToday)
        driver.close()
    }

    @Test
    fun reversedNotetypeCreatesTwoStudyableCards() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))

        store.addLocalNote(
            AddNoteDraft(
                deckName = "Vocab",
                front = "chien",
                back = "dog",
                notetypeId = NotetypeCatalog.BasicReversedId,
                cardOrds = listOf(0, 1),
            ),
            nowMillis = 1_000_000L,
            noteGuid = "local-reversed",
        )

        val content = store.loadLocalContent()
        assertEquals(2, content.cardCount)
        val cards = SyncedCollection().withLocalContent(content)
            .asDecks(
                nowMillis = 1_000_000L,
                deckOptions = mapOf("Vocab" to DeckOptions(buryNewSiblings = false)),
            ).single().cards
        assertEquals(2, cards.size)
        assertEquals(setOf("chien", "dog"), cards.map { it.front }.toSet())
        assertEquals(setOf("chien", "dog"), cards.map { it.back }.toSet())
        driver.close()
    }

    @Test
    fun locallyAddedCardSurvivesRestartOverlayAndDownloadedSync() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val draft = AddNoteDraft(
            deckName = "Languages",
            front = "  bonjour  ",
            back = "  hello  ",
            tags = listOf("french", "greeting", "french"),
        )

        val added = store.addLocalNote(draft, 1_000_000L, "local-note-test")
        val card = added.content.cards.values.single()
        assertTrue(card.cardId < 0)
        assertEquals("Languages", card.deckName)
        assertEquals(listOf("bonjour", "hello"), added.content.notes.getValue("local-note-test").fields)
        assertEquals(listOf("french", "greeting"), added.content.notes.getValue("local-note-test").tags)
        val rendered = SyncedCollection().withLocalContent(added.content)
            .asDecks(nowMillis = 1_000_000L).single().cards.single()
        assertEquals("bonjour", rendered.front)
        assertEquals("hello", rendered.back)
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(serverTime = "first-sync"),
            1_000_000L,
        )
        assertEquals(1, store.loadLocalContent().cardCount)

        val reviewed = store.recordReview(card, Rating.Good, 1_000_000L)
        val afterSync = store.replaceCollection(SyncedCollection(serverTime = "cursor"), 1_000_000L)
        assertEquals(reviewed.schedule, afterSync.schedules[card.cardId])
        val restoredContent = store.loadLocalContent()
        assertEquals(added.content, restoredContent)
        assertEquals(1, SyncedCollection().withLocalContent(restoredContent).cards.size)
        driver.close()
    }

    @Test
    fun credentialsAndCompleteCollectionSurviveRoundTrip() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val auth = StoredSyncAuth(
            token = "secret-token",
            clientId = "client-1",
            endpoint = DefaultKelmaSyncEndpoint,
            username = "test@example.com",
        )
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(
                    guid = "note-1",
                    notetypeId = 10,
                    fields = listOf("front", "back"),
                    tags = listOf("tag"),
                ),
            ),
            cards = mapOf(20L to SyncCard(20, "note-1", "Deck")),
            reviews = mapOf(30L to SyncReview(30, noteGuid = "note-1")),
            studyDays = mapOf(
                "20000\u0000Deck" to SyncStudyDay(20_000, "Deck", reviewStudied = 1),
            ),
            notetypes = mapOf(10L to SyncNotetype(10, "Basic")),
            deckRecords = mapOf("Deck" to SyncDeck("Deck")),
            media = mapOf(
                "audio.mp3" to SyncMediaFile("audio.mp3", "now", byteArrayOf(1, 2, 3)),
            ),
            deckNames = setOf("Deck"),
            serverTime = "2026-07-25T12:00:00Z",
        )

        store.saveSignedInState(auth, collection)
        val restored = store.load()

        assertEquals(auth, restored.auth)
        assertEquals("front", restored.collection.notes.getValue("note-1").fields.first())
        assertEquals("Deck", restored.collection.cards.getValue(20).deckName)
        assertEquals(1, restored.collection.reviews.size)
        assertEquals(1, restored.collection.studyDays.size)
        assertEquals("Basic", restored.collection.notetypes.getValue(10).name)
        assertTrue("Deck" in restored.collection.deckNames)
        assertTrue(restored.collection.media.getValue("audio.mp3").bytes.isEmpty())
        assertEquals(3L, restored.collection.media.getValue("audio.mp3").sizeBytes)
        assertEquals("2026-07-25T12:00:00Z", restored.collection.serverTime)
        driver.close()
    }

    @Test
    fun failedReviewEventInsertRollsBackScheduleUpdate() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(20, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1")),
            cards = mapOf(card.cardId to card),
            deckNames = setOf("Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
        )
        driver.execute(
            identifier = null,
            sql = """
                CREATE TRIGGER reject_local_review
                BEFORE INSERT ON local_review_events
                BEGIN SELECT RAISE(ABORT, 'forced review failure'); END
            """.trimIndent(),
            parameters = 0,
        )

        assertFailsWith<Exception> { store.recordReview(card, Rating.Good, 1_000_000L) }
        val restored = store.loadLocalReviews(1_000_000L)
        assertTrue(restored.schedules.isEmpty())
        assertEquals(0, restored.reviewedToday)
        assertNull(restored.lastReviewDeck)
        driver.close()
    }

    @Test
    fun reviewsSurviveSyncAndUndoTransactionally() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val now = 1_000_000L
        val card = SyncCard(20, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            deckNames = setOf("Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
            now,
        )

        val first = store.recordReview(card, Rating.Good, now, durationMillis = 2_500)
        assertEquals(1, first.snapshot.reviewedToday)
        assertEquals("Deck", first.snapshot.lastReviewDeck)
        assertEquals(ReviewPhase.Learning, first.schedule.phase)
        val restored = store.load(now).localReviews
        assertEquals(first.schedule, restored.schedules[card.cardId])
        assertEquals("Deck", restored.lastReviewDeck)

        val afterSync = store.replaceCollection(collection, now)
        assertEquals(first.schedule, afterSync.schedules.getValue(card.cardId))
        assertEquals(1, afterSync.reviewedToday)

        val secondTime = first.schedule.dueAtMillis
        store.recordReview(card, Rating.Again, secondTime)
        val undone = assertNotNull(store.undoLastReview("Deck", secondTime))
        assertEquals(card.cardId, undone.cardId)
        assertEquals(first.schedule, undone.snapshot.schedules[card.cardId])
        assertEquals(1, undone.snapshot.reviewedToday)
        assertEquals("Deck", undone.snapshot.lastReviewDeck)
        driver.close()
    }

    @Test
    fun localReviewsAreScopedToTheSignedInAccount() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(20, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1")),
            cards = mapOf(card.cardId to card),
            deckNames = setOf("Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client-1", DefaultKelmaSyncEndpoint, "user-a"),
            collection,
        )
        store.recordReview(card, Rating.Good)
        store.addLocalNote(
            AddNoteDraft("Local", "front", "back"),
            noteGuid = "local-account-test",
        )
        store.createLocalDeck("Empty local")

        val sameAccount = store.saveSignedInState(
            StoredSyncAuth("new-token", "client-2", DefaultKelmaSyncEndpoint, "user-a"),
            collection,
        )
        assertTrue(sameAccount.schedules.isNotEmpty())
        assertEquals(1, store.loadLocalContent().cardCount)
        assertTrue("Empty local" in store.loadLocalContent().deckNames)
        val otherAccount = store.saveSignedInState(
            StoredSyncAuth("token-b", "client-b", DefaultKelmaSyncEndpoint, "user-b"),
            collection,
        )
        assertTrue(otherAccount.schedules.isEmpty())
        assertNull(otherAccount.lastReviewDeck)
        assertEquals(0, store.loadLocalContent().cardCount)
        assertTrue(store.loadLocalContent().deckNames.isEmpty())
        driver.close()
    }

    @Test
    fun undoingFirstReviewReturnsCardToDownloadedState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(20, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            deckNames = setOf("Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
            1_000_000L,
        )
        store.recordReview(card, Rating.Good, 1_000_000L)

        val undone = assertNotNull(store.undoLastReview("Deck", 1_000_000L))

        assertTrue(undone.snapshot.schedules.isEmpty())
        assertEquals(0, undone.snapshot.reviewedToday)
        assertNull(undone.snapshot.lastReviewDeck)
        driver.close()
    }

    @Test
    fun redownloadResetClearsOnlyDownloadedStateAndPreservesAccountAndLocalWork() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = mapOf("downloaded" to SyncNote("downloaded")),
                cards = mapOf(20L to SyncCard(20L, "downloaded", "Remote")),
                deckNames = setOf("Remote"),
                media = mapOf("sound.mp3" to SyncMediaFile("sound.mp3", "v1", byteArrayOf(1, 2, 3))),
                serverTime = "cursor",
            ),
        )
        store.addLocalNote(
            AddNoteDraft("Local", "front", "back"),
            noteGuid = "local-redownload-test",
        )

        val reset = store.resetDownloadedCollectionForRedownload()

        assertEquals("user", reset.auth?.username)
        assertNull(reset.collection.serverTime)
        assertTrue(reset.collection.notes.isEmpty())
        assertTrue(reset.collection.cards.isEmpty())
        assertTrue(reset.collection.media.isEmpty())
        assertTrue(reset.collection.deckNames.isEmpty())
        assertTrue(reset.localContent.notes.containsKey("local-redownload-test"))
        assertTrue(store.prepareSyncUpload().notes.any { it.guid == "local-redownload-test" })
        driver.close()
    }

    @Test
    fun signOutClearsCredentialsDownloadedDataAndReviews() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(20, "note-1", "Deck")
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = mapOf("note-1" to SyncNote("note-1")),
                cards = mapOf(card.cardId to card),
                deckNames = setOf("Deck"),
            ),
        )
        store.recordReview(card, Rating.Good)
        store.addLocalNote(
            AddNoteDraft("Local", "front", "back"),
            noteGuid = "local-signout-test",
        )

        store.clearAll()
        val restored = store.load()

        assertNull(restored.auth)
        assertTrue(restored.collection.deckNames.isEmpty())
        assertTrue(restored.localContent.cards.isEmpty())
        assertTrue(restored.localContent.notes.isEmpty())
        assertTrue(restored.localContent.deckNames.isEmpty())
        assertTrue(restored.localContent.deckOptions.isEmpty())
        assertTrue(restored.localReviews.schedules.isEmpty())
        assertEquals(0, restored.localReviews.reviewedToday)
        assertNull(restored.localReviews.lastReviewDeck)
        driver.close()
    }
}

private fun JdbcSqliteDriver.createLegacyMediaTable() {
    execute(
        null,
        "CREATE TABLE sync_media (filename TEXT PRIMARY KEY, modified_at TEXT NOT NULL, bytes BLOB NOT NULL)",
        0,
    )
}

private fun JdbcSqliteDriver.createLegacyAuthTable() {
    execute(
        null,
        """CREATE TABLE sync_auth (
            singleton_id INTEGER PRIMARY KEY, token TEXT NOT NULL, client_id TEXT NOT NULL,
            endpoint TEXT NOT NULL, username TEXT NOT NULL
        )""".trimIndent(),
        0,
    )
}

private fun newCardScheduling() = buildJsonObject {
    put("queue", 0)
    put("ivl", 0)
    put("reps", 0)
}
