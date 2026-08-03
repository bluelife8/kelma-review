package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import tech.kelma.db.KelmaDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowseIndexPersistenceTest {
    @Test
    fun indexPersistsAndReturnsBoundedPages() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val collection = indexedCollection(230)
        val request = BrowsePageRequest("", BrowseSorting(), 1_000L, offset = 0, limit = 50)

        val first = store.loadBrowsePage(collection, request)
        val second = store.loadBrowsePage(collection, request.copy(offset = 50))

        assertEquals(230, first.totalCount)
        assertEquals(50, first.rows.size)
        assertEquals(50, second.rows.size)
        assertTrue(first.rows.last().question < second.rows.first().question)
        assertEquals(listOf("French" to 115, "Spanish" to 115), first.decks)
        assertEquals(emptyList(), second.decks)

        assertEquals(0L, database.kelmaQueries.selectBrowseIndexDirty().executeAsOne())
        val restored = PersistentCollectionStore(database).loadBrowsePage(collection, request)
        assertEquals(first.rows.map(BrowseCardRow::cardId), restored.rows.map(BrowseCardRow::cardId))
        assertEquals(0L, database.kelmaQueries.selectBrowseIndexDirty().executeAsOne())

        store.addLocalNote(
            AddNoteDraft("French", "new front", "new back"),
            nowMillis = 2_000L,
            noteGuid = "dirty-note",
        )
        assertEquals(1L, database.kelmaQueries.selectBrowseIndexDirty().executeAsOne())
        val updatedCollection = collection.withLocalContent(store.loadLocalContent())
        val updated = store.loadBrowsePage(updatedCollection, request.copy(queryId = "updated"))
        assertEquals(231, updated.totalCount)
        assertEquals(0L, database.kelmaQueries.selectBrowseIndexDirty().executeAsOne())
        driver.close()
    }

    @Test
    fun pageRowsRenderTemplatesOnlyAfterSqlSelectsTheirIds() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val collection = SyncedCollection(
            notes = mapOf(
                "note" to SyncNote(
                    "note",
                    notetypeId = NotetypeCatalog.BasicReversedId,
                    fields = listOf("front field", "back field"),
                ),
            ),
            cards = mapOf(1L to SyncCard(1, "note", "Deck", ord = 1)),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("Deck"),
        )

        val page = store.loadBrowsePage(
            collection,
            BrowsePageRequest("", BrowseSorting(), 1_000L, 0, 20),
        )

        assertEquals("back field", page.rows.single().question)
        assertEquals("front field", page.rows.single().answer)
        driver.close()
    }

    @Test
    fun dueDateProjectionUsesNewestLocalOrRemoteOverride() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val card = SyncCard(
            cardId = 1,
            noteGuid = "note",
            deckName = "Deck",
            dueDateOverrideMillis = 20_000L,
            dueDateOverrideClientModifiedAt = epochMillisToRfc3339(2_000L),
        )
        val collection = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(1L to card),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("Deck"),
        )
        upsertLocalSchedule(
            database.kelmaQueries,
            LocalCardSchedule(1, ReviewPhase.Review, 30_000L, 2.0, 5.0, 2, 2, 0, 1_000L),
        )
        database.kelmaQueries.upsertLocalCardDueOverride("note", 0, 1, 10_000L, 1_000L)
        val request = BrowsePageRequest("", BrowseSorting(BrowseSort.Due), 40_000L, 0, 20)

        assertEquals(20_000L, store.loadBrowsePage(collection, request.copy(queryId = "remote")).rows.single().dueMillis)

        database.kelmaQueries.upsertLocalCardDueOverride("note", 0, 1, 0L, 3_000L)
        assertEquals(30_000L, store.loadBrowsePage(collection, request.copy(queryId = "cleared")).rows.single().dueMillis)

        database.kelmaQueries.upsertLocalCardDueOverride("note", 0, 1, 5_000L, 4_000L)
        assertEquals(5_000L, store.loadBrowsePage(collection, request.copy(queryId = "local")).rows.single().dueMillis)
        driver.close()
    }

    @Test
    fun sqlQueryPreservesBrowseQualifiersStateAndSorting() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val now = 10_000L
        val collection = SyncedCollection(
            notes = mapOf(
                "a" to SyncNote("a", fields = listOf("Zulu", "answer"), tags = listOf("marked", "one")),
                "b" to SyncNote("b", fields = listOf("Alpha", "answer"), tags = listOf("two")),
                "c" to SyncNote("c", fields = listOf("Beta", "answer"), tags = listOf("marked")),
                "d" to SyncNote("d", fields = listOf("Gamma", "answer"), tags = listOf("marked")),
            ),
            cards = mapOf(
                1L to SyncCard(1, "a", "French", createdAt = "2024-01-01T00:00:00.000Z"),
                2L to SyncCard(2, "b", "French", createdAt = "2024-01-02T00:00:00.000Z"),
                3L to SyncCard(
                    3,
                    "c",
                    "Spanish",
                    studyState = CardStudyState.Suspended,
                ),
                -4L to SyncCard(-4, "d", "French", createdAt = "2024-01-03T00:00:00.000Z"),
            ),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("French", "Spanish"),
        )
        val schedules = mapOf(
            1L to LocalCardSchedule(
                cardId = 1,
                phase = ReviewPhase.Review,
                dueAtMillis = now - 1,
                stability = 2.0,
                difficulty = 5.0,
                scheduledDays = 2,
                repetitions = 2,
                lapses = 0,
                lastReviewAtMillis = now - MillisPerDay,
            ),
            2L to LocalCardSchedule(
                cardId = 2,
                phase = ReviewPhase.Learning,
                dueAtMillis = now + MillisPerDay,
                stability = 0.0,
                difficulty = 5.0,
                scheduledDays = 0,
                repetitions = 1,
                lapses = 0,
                lastReviewAtMillis = now,
            ),
        )
        schedules.values.forEach { upsertLocalSchedule(database.kelmaQueries, it) }

        val markedFrench = store.loadBrowsePage(
            collection,
            BrowsePageRequest(
                query = "deck:French tag:marked",
                sorting = BrowseSorting(BrowseSort.Question),
                nowMillis = now,
                offset = 0,
                limit = 20,
            ),
        )
        val review = store.loadBrowsePage(
            collection,
            BrowsePageRequest("is:review", BrowseSorting(), now, 0, 20),
        )
        val suspended = store.loadBrowsePage(
            collection,
            BrowsePageRequest("is:suspended", BrowseSorting(), now, 0, 20),
        )
        val local = store.loadBrowsePage(
            collection,
            BrowsePageRequest("is:local", BrowseSorting(), now, 0, 20),
        )

        assertEquals(listOf("Gamma", "Zulu"), markedFrench.rows.map(BrowseCardRow::question))
        assertEquals(listOf(1L), review.rows.map(BrowseCardRow::cardId))
        assertEquals(listOf(3L), suspended.rows.map(BrowseCardRow::cardId))
        assertEquals(listOf(-4L), local.rows.map(BrowseCardRow::cardId))

        val allRows = collection.browseRows(schedules)
        val queries = listOf(
            "",
            "answer marked",
            "deck:French",
            "tag:marked",
            "note:Basic",
            "is:new",
            "is:learning",
            "is:review",
            "is:suspended",
            "is:due",
            "is:local",
            "is:unknown",
            "created:2024-01-02",
            "created:2024-01-01..2024-01-02",
            "created:unknown",
            "created:2024-02-30",
        )
        queries.forEach { query ->
            BrowseSort.entries.forEach { field ->
                listOf(true, false).forEach { ascending ->
                    val sorting = BrowseSorting(field, ascending)
                    val expected = allRows
                        .filter { it.matches(parseBrowseQuery(query), now) }
                        .sortedForBrowse(sorting)
                        .map(BrowseCardRow::cardId)
                    val actual = store.loadBrowsePage(
                        collection,
                        BrowsePageRequest(query, sorting, now, 0, 100),
                    ).rows.map(BrowseCardRow::cardId)
                    assertEquals(expected, actual, "$query · $sorting")
                }
            }
        }
        driver.close()
    }

    private fun indexedCollection(size: Int): SyncedCollection {
        val notes = (1..size).associate { index ->
            val id = "note-$index"
            id to SyncNote(
                guid = id,
                fields = listOf("front ${index.toString().padStart(3, '0')}", "answer $index"),
                tags = listOf(if (index % 3 == 0) "third" else "other"),
            )
        }
        val cards = (1..size).associate { index ->
            index.toLong() to SyncCard(
                cardId = index.toLong(),
                noteGuid = "note-$index",
                deckName = if (index % 2 == 0) "French" else "Spanish",
            )
        }
        return SyncedCollection(
            notes = notes,
            cards = cards,
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("French", "Spanish"),
        )
    }
}
