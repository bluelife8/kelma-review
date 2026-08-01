package tech.kelma.app

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

internal class AnkiSqliteCodec(private val files: TemporarySqliteFiles) {
    fun encode(snapshot: AnkiDatabaseSnapshot): ByteArray = files.open().use { temporary ->
        val driver = temporary.driver
        createLegacySchema(driver)
        AnkiTransacter(driver).transaction {
            insertCollection(driver, snapshot.collection)
            snapshot.notes.forEach { insertNote(driver, it) }
            snapshot.cards.forEach { insertCard(driver, it) }
            snapshot.reviews.forEach { insertReview(driver, it) }
        }
        driver.execute(null, "ANALYZE", 0)
        temporary.readBytes()
    }

    fun decode(bytes: ByteArray): AnkiDatabaseSnapshot = files.open(bytes).use { temporary ->
        val driver = temporary.driver
        // Recent Anki databases use a private `unicase` collation in indexes. Writable-schema
        // mode lets SQLite read table rows without requiring that index collation.
        driver.execute(null, "PRAGMA writable_schema = ON", 0)
        require(hasTable(driver, "col") && hasTable(driver, "notes") && hasTable(driver, "cards")) {
            "The package does not contain an Anki collection"
        }
        val collection = queryOne(driver, "SELECT * FROM col LIMIT 1") { cursor ->
            AnkiCollectionRow(
                id = cursor.long(0),
                createdAtSeconds = cursor.long(1),
                modifiedAtMillis = cursor.long(2),
                schemaModifiedAtMillis = cursor.long(3),
                schemaVersion = cursor.long(4).toInt(),
                updateSequence = cursor.long(6),
                lastSync = cursor.long(7),
                configurationJson = cursor.string(8),
                modelsJson = cursor.string(9),
                decksJson = cursor.string(10),
                deckConfigurationsJson = cursor.string(11),
                tagsJson = cursor.string(12),
            )
        }
        AnkiDatabaseSnapshot(
            collection = collection,
            notes = queryList(driver, "SELECT id,guid,mid,mod,usn,tags,flds,sfld,csum,flags,data FROM notes") {
                AnkiNoteRow(
                    id = it.long(0), guid = it.string(1), notetypeId = it.long(2),
                    modifiedAtSeconds = it.long(3), updateSequence = it.long(4), tags = it.string(5),
                    fields = it.string(6), sortField = it.string(7), checksum = it.long(8),
                    flags = it.long(9).toInt(), data = it.string(10),
                )
            },
            cards = queryList(
                driver,
                "SELECT id,nid,did,ord,mod,usn,type,queue,due,ivl,factor,reps,lapses," +
                    "left,odue,odid,flags,data FROM cards",
            ) {
                AnkiCardRow(
                    id = it.long(0), noteId = it.long(1), deckId = it.long(2), ordinal = it.long(3).toInt(),
                    modifiedAtSeconds = it.long(4), updateSequence = it.long(5), type = it.long(6).toInt(),
                    queue = it.long(7).toInt(), due = it.long(8), interval = it.long(9).toInt(),
                    factor = it.long(10).toInt(), repetitions = it.long(11).toInt(), lapses = it.long(12).toInt(),
                    remainingSteps = it.long(13).toInt(), originalDue = it.long(14), originalDeckId = it.long(15),
                    flags = it.long(16).toInt(), data = it.string(17),
                )
            },
            reviews = if (hasTable(driver, "revlog")) queryList(
                driver,
                "SELECT id,cid,usn,ease,ivl,lastIvl,factor,time,type FROM revlog",
            ) {
                AnkiReviewRow(
                    id = it.long(0), cardId = it.long(1), updateSequence = it.long(2),
                    ease = it.long(3).toInt(), interval = it.long(4).toInt(),
                    previousInterval = it.long(5).toInt(), factor = it.long(6).toInt(),
                    durationMillis = it.long(7).toInt(), type = it.long(8).toInt(),
                )
            } else emptyList(),
            normalizedNotetypes = if (hasTable(driver, "notetypes")) queryList(
                driver,
                "SELECT id,name,mtime_secs,usn,config FROM notetypes",
            ) {
                AnkiNormalizedNotetype(it.long(0), it.string(1), it.long(2), it.long(3), it.bytes(4))
            } else emptyList(),
            normalizedFields = if (hasTable(driver, "fields")) queryList(
                driver,
                "SELECT ntid,ord,name,config FROM fields ORDER BY ntid,ord",
            ) {
                AnkiNormalizedField(it.long(0), it.long(1).toInt(), it.string(2), it.bytes(3))
            } else emptyList(),
            normalizedTemplates = if (hasTable(driver, "templates")) queryList(
                driver,
                "SELECT ntid,ord,name,mtime_secs,usn,config FROM templates ORDER BY ntid,ord",
            ) {
                AnkiNormalizedTemplate(
                    it.long(0), it.long(1).toInt(), it.string(2), it.long(3), it.long(4), it.bytes(5),
                )
            } else emptyList(),
            normalizedDecks = if (hasTable(driver, "decks")) queryList(
                driver,
                "SELECT id,name,mtime_secs,usn,common,kind FROM decks",
            ) {
                AnkiNormalizedDeck(it.long(0), it.string(1), it.long(2), it.long(3), it.bytes(4), it.bytes(5))
            } else emptyList(),
            normalizedDeckConfigs = if (hasTable(driver, "deck_config")) queryList(
                driver,
                "SELECT id,name,mtime_secs,usn,config FROM deck_config",
            ) {
                AnkiNormalizedDeckConfig(it.long(0), it.string(1), it.long(2), it.long(3), it.bytes(4))
            } else emptyList(),
        )
    }
}

private fun createLegacySchema(driver: SqlDriver) {
    driver.execute(
        null,
        """CREATE TABLE col (
            id integer primary key, crt integer not null, mod integer not null, scm integer not null,
            ver integer not null, dty integer not null, usn integer not null, ls integer not null,
            conf text not null, models text not null, decks text not null, dconf text not null, tags text not null
        )""".trimIndent(),
        0,
    )
    driver.execute(
        null,
        """CREATE TABLE notes (
            id integer primary key, guid text not null, mid integer not null, mod integer not null,
            usn integer not null, tags text not null, flds text not null, sfld integer not null,
            csum integer not null, flags integer not null, data text not null
        )""".trimIndent(),
        0,
    )
    driver.execute(
        null,
        """CREATE TABLE cards (
            id integer primary key, nid integer not null, did integer not null, ord integer not null,
            mod integer not null, usn integer not null, type integer not null, queue integer not null,
            due integer not null, ivl integer not null, factor integer not null, reps integer not null,
            lapses integer not null, left integer not null, odue integer not null, odid integer not null,
            flags integer not null, data text not null
        )""".trimIndent(),
        0,
    )
    driver.execute(
        null,
        """CREATE TABLE revlog (
            id integer primary key, cid integer not null, usn integer not null, ease integer not null,
            ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null,
            type integer not null
        )""".trimIndent(),
        0,
    )
    driver.execute(null, "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)", 0)
    listOf(
        "CREATE INDEX ix_cards_nid ON cards (nid)",
        "CREATE INDEX ix_cards_sched ON cards (did, queue, due)",
        "CREATE INDEX ix_cards_usn ON cards (usn)",
        "CREATE INDEX ix_notes_csum ON notes (csum)",
        "CREATE INDEX ix_notes_usn ON notes (usn)",
        "CREATE INDEX ix_revlog_cid ON revlog (cid)",
        "CREATE INDEX ix_revlog_usn ON revlog (usn)",
    ).forEach { driver.execute(null, it, 0) }
}

private fun insertCollection(driver: SqlDriver, row: AnkiCollectionRow) {
    driver.execute(null, "INSERT INTO col VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", 13) {
        bindLong(0, row.id)
        bindLong(1, row.createdAtSeconds)
        bindLong(2, row.modifiedAtMillis)
        bindLong(3, row.schemaModifiedAtMillis)
        bindLong(4, row.schemaVersion.toLong())
        bindLong(5, 0)
        bindLong(6, row.updateSequence)
        bindLong(7, row.lastSync)
        bindString(8, row.configurationJson)
        bindString(9, row.modelsJson)
        bindString(10, row.decksJson)
        bindString(11, row.deckConfigurationsJson)
        bindString(12, row.tagsJson)
    }
}

private fun insertNote(driver: SqlDriver, row: AnkiNoteRow) {
    driver.execute(null, "INSERT INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?)", 11) {
        bindLong(0, row.id)
        bindString(1, row.guid)
        bindLong(2, row.notetypeId)
        bindLong(3, row.modifiedAtSeconds)
        bindLong(4, row.updateSequence)
        bindString(5, row.tags)
        bindString(6, row.fields)
        bindString(7, row.sortField)
        bindLong(8, row.checksum)
        bindLong(9, row.flags.toLong())
        bindString(10, row.data)
    }
}

private fun insertCard(driver: SqlDriver, row: AnkiCardRow) {
    driver.execute(null, "INSERT INTO cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", 18) {
        bindLong(0, row.id)
        bindLong(1, row.noteId)
        bindLong(2, row.deckId)
        bindLong(3, row.ordinal.toLong())
        bindLong(4, row.modifiedAtSeconds)
        bindLong(5, row.updateSequence)
        bindLong(6, row.type.toLong())
        bindLong(7, row.queue.toLong())
        bindLong(8, row.due)
        bindLong(9, row.interval.toLong())
        bindLong(10, row.factor.toLong())
        bindLong(11, row.repetitions.toLong())
        bindLong(12, row.lapses.toLong())
        bindLong(13, row.remainingSteps.toLong())
        bindLong(14, row.originalDue)
        bindLong(15, row.originalDeckId)
        bindLong(16, row.flags.toLong())
        bindString(17, row.data)
    }
}

private fun insertReview(driver: SqlDriver, row: AnkiReviewRow) {
    driver.execute(null, "INSERT INTO revlog VALUES (?,?,?,?,?,?,?,?,?)", 9) {
        bindLong(0, row.id)
        bindLong(1, row.cardId)
        bindLong(2, row.updateSequence)
        bindLong(3, row.ease.toLong())
        bindLong(4, row.interval.toLong())
        bindLong(5, row.previousInterval.toLong())
        bindLong(6, row.factor.toLong())
        bindLong(7, row.durationMillis.toLong())
        bindLong(8, row.type.toLong())
    }
}

private fun hasTable(driver: SqlDriver, name: String): Boolean = queryList(
    driver,
    "SELECT name FROM sqlite_master WHERE type='table' AND name='${name.replace("'", "''")}'",
) { it.string(0) }.isNotEmpty()

private fun <T> queryOne(driver: SqlDriver, sql: String, map: (SqlCursor) -> T): T =
    queryList(driver, sql, map).firstOrNull() ?: error("Anki collection metadata is missing")

private fun <T> queryList(driver: SqlDriver, sql: String, map: (SqlCursor) -> T): List<T> =
    driver.executeQuery(null, sql, { cursor ->
        QueryResult.Value(buildList { while (cursor.next().value) add(map(cursor)) })
    }, 0).value

private class AnkiTransacter(driver: SqlDriver) : TransacterImpl(driver)

private fun SqlCursor.long(index: Int): Long = getLong(index) ?: 0L
private fun SqlCursor.string(index: Int): String = getString(index).orEmpty()
private fun SqlCursor.bytes(index: Int): ByteArray = getBytes(index) ?: ByteArray(0)
