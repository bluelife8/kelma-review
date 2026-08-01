package tech.kelma.app

import androidx.compose.runtime.Composable
import app.cash.sqldelight.db.SqlDriver

internal const val MaxInterchangeFileBytes = 512 * 1024 * 1024

data class InterchangeDocument(
    val filename: String,
    val bytes: ByteArray,
)

interface CollectionDocumentIO {
    suspend fun open(): InterchangeDocument?

    suspend fun openPlugin(): InterchangeDocument? = open()

    suspend fun save(filename: String, mimeType: String, bytes: ByteArray): Boolean
}

interface TemporarySqliteFile : AutoCloseable {
    val driver: SqlDriver

    /** Returns a consistent snapshot of the temporary database. */
    fun readBytes(): ByteArray
}

interface TemporarySqliteFiles {
    fun open(initialBytes: ByteArray? = null): TemporarySqliteFile
}

data class CollectionInterchangePlatform(
    val documents: CollectionDocumentIO,
    val sqliteFiles: TemporarySqliteFiles,
)

@Composable
expect fun rememberCollectionInterchangePlatform(): CollectionInterchangePlatform
