package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class MediaAttachmentPersistenceTest {
    @Test
    fun attachmentUsesDurableOutboxCacheAndConfirmingPull() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val cache = RecordingMediaCache()
        val store = PersistentCollectionStore(KelmaDatabase(driver), mediaCache = cache)
        val bytes = byteArrayOf(1, 2, 3, 4)

        val saved = store.saveMediaAttachment("folder/photo one.png", "image/png", bytes, 100L)
        assertEquals("photo one.png", saved.filename)
        var local = saved.content
        val attachment = local.media.getValue("photo one.png")
        assertContentEquals(bytes, attachment.bytes)
        assertEquals(64, attachment.checksum.length)
        assertContentEquals(bytes, cache.read("photo one.png"))

        val upload = store.prepareSyncUpload().media.single()
        assertEquals("photo one.png", upload.filename)
        assertEquals("image/png", upload.mimeType)
        assertContentEquals(bytes, upload.bytes)
        val collision = store.saveMediaAttachment("photo one.png", "image/png", byteArrayOf(9), 101L)
        assertTrue(collision.filename.matches(Regex("photo one-[0-9a-f]{8}\\.png")))

        store.applySyncPushResult(SyncPushResult(uploadedMediaFilenames = setOf(upload.filename)))
        assertEquals("uploaded", store.loadLocalContent().media.getValue(upload.filename).uploadState)

        val stagingDataKey = syncMediaStagingDataKey(upload.filename)
        val stagingVersionKey = syncMediaStagingVersionKey(upload.filename)
        cache.write(stagingDataKey, bytes)
        cache.write(stagingVersionKey, "server".encodeToByteArray())
        store.replaceCollection(
            SyncedCollection(
                media = mapOf(upload.filename to SyncMediaFile(upload.filename, "server", bytes)),
            ),
        )
        assertEquals(null, cache.read(stagingDataKey))
        assertEquals(null, cache.read(stagingVersionKey))
        local = store.loadLocalContent()
        assertEquals(setOf(collision.filename), local.media.keys)
        val downloaded = store.load().collection.media.getValue(upload.filename)
        assertTrue(downloaded.bytes.isEmpty())
        assertEquals(bytes.size.toLong(), downloaded.sizeBytes)
        assertContentEquals(bytes, cache.read(upload.filename))
        driver.close()
    }

    @Test
    fun contentOnlyPullPreservesMediaWithoutRewritingItsCacheOrDatabaseBlob() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val cache = RecordingMediaCache()
        val store = PersistentCollectionStore(database, mediaCache = cache)
        val bytes = byteArrayOf(4, 5, 6)
        val initial = SyncedCollection(
            media = mapOf("stable.mp3" to SyncMediaFile("stable.mp3", "server", bytes)),
        )
        store.replaceCollection(initial)

        store.replaceCollection(
            initial.copy(notes = mapOf("new-note" to SyncNote("new-note"))),
            mediaFilenamesToCache = emptySet(),
            preserveDownloadedMedia = true,
        )

        assertEquals(1, cache.writeCount)
        val stored = database.kelmaQueries.selectMedia { _, _, content, _ -> content }.executeAsOne()
        assertContentEquals(bytes, stored)
        driver.close()
    }

    @Test
    fun missingRemoteBlobIsRequeuedFromTheDurableDownloadedCopy() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val bytes = byteArrayOf(8, 9)
        store.replaceCollection(
            SyncedCollection(media = mapOf("repair.mp3" to SyncMediaFile("repair.mp3", "server", bytes))),
        )

        assertEquals(1, store.queueMissingRemoteMedia(setOf("repair.mp3"), 200L))
        val repair = store.prepareSyncUpload().media.single()
        assertEquals("repair.mp3", repair.filename)
        assertContentEquals(bytes, repair.bytes)
        driver.close()
    }

    @Test
    fun legacyDownloadedBlobMigratesToDiskWithoutRemainingInTheCollectionHeap() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val cache = RecordingMediaCache()
        val bytes = byteArrayOf(6, 7, 8, 9)
        PersistentCollectionStore(database, mediaCache = cache).replaceCollection(
            SyncedCollection(media = mapOf("legacy.mp3" to SyncMediaFile("legacy.mp3", "v1", bytes))),
        )
        cache.clear()

        val restored = PersistentCollectionStore(database, mediaCache = cache).load().collection

        assertContentEquals(bytes, cache.read("legacy.mp3"))
        assertTrue(restored.media.getValue("legacy.mp3").bytes.isEmpty())
        assertEquals(bytes.size.toLong(), restored.media.getValue("legacy.mp3").sizeBytes)
        val databaseBytes = database.kelmaQueries.selectMedia { _, _, content, _ -> content }.executeAsOne()
        assertTrue(databaseBytes.isEmpty())
        driver.close()
    }

    @Test
    fun attachmentValidationAndJvmCacheAreSafeAndAtomic() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        assertFailsWith<IllegalArgumentException> {
            store.saveMediaAttachment("bad.exe", "application/octet-stream", byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveMediaAttachment("empty.png", "image/png", byteArrayOf())
        }
        driver.close()

        val directory = Files.createTempDirectory("kelma-media-cache").toFile()
        try {
            val cache = JvmMediaCache(directory)
            cache.write("../safe.png", byteArrayOf(7, 8))
            assertContentEquals(byteArrayOf(7, 8), cache.read("../safe.png"))
            assertTrue(directory.listFiles().orEmpty().all { it.name.matches(Regex("[0-9a-f]{64}")) })
            cache.clear()
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class RecordingMediaCache : MediaCache {
    private val values = mutableMapOf<String, ByteArray>()
    var writeCount: Int = 0
        private set

    override fun read(filename: String): ByteArray? = values[filename]
    override fun write(filename: String, bytes: ByteArray) {
        writeCount++
        values[filename] = bytes.copyOf()
    }
    override fun delete(filename: String) { values.remove(filename) }
    override fun clear() { values.clear() }
}
