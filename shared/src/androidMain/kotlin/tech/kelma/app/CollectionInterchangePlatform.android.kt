package tech.kelma.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun rememberCollectionInterchangePlatform(): CollectionInterchangePlatform {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val documents = remember(context, scope) { AndroidCollectionDocumentIO(context, scope) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), documents::opened)
    val binarySaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        documents::saveDestination,
    )
    val textSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
        documents::saveDestination,
    )
    val jsonSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
        documents::saveDestination,
    )
    documents.launchOpen = openLauncher::launch
    documents.launchSave = { filename, mimeType ->
        when {
            mimeType == "application/json" -> jsonSaveLauncher.launch(filename)
            mimeType.startsWith("text/") -> textSaveLauncher.launch(filename)
            else -> binarySaveLauncher.launch(filename)
        }
    }
    return remember(context, documents) {
        CollectionInterchangePlatform(documents, AndroidTemporarySqliteFiles(context))
    }
}

private class AndroidCollectionDocumentIO(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : CollectionDocumentIO {
    var launchOpen: (Array<String>) -> Unit = {}
    var launchSave: (String, String) -> Unit = { _, _ -> }
    private var pendingOpen: CompletableDeferred<InterchangeDocument?>? = null
    private var pendingOpenMaximum = MaxInterchangeFileBytes
    private var pendingSave: PendingSave? = null

    override suspend fun open(): InterchangeDocument? = openDocument(
        maximumBytes = MaxInterchangeFileBytes,
        mimeTypes = arrayOf(
            "application/octet-stream",
            "application/zip",
            "application/json",
            "text/plain",
            "text/tab-separated-values",
        ),
    )

    override suspend fun openPlugin(): InterchangeDocument? = openDocument(
        maximumBytes = MaximumPluginPackageBytes,
        mimeTypes = arrayOf("application/octet-stream", "application/zip"),
    )

    private suspend fun openDocument(maximumBytes: Int, mimeTypes: Array<String>): InterchangeDocument? {
        check(pendingOpen == null && pendingSave == null) { "A document picker is already open" }
        return CompletableDeferred<InterchangeDocument?>().also { deferred ->
            pendingOpen = deferred
            pendingOpenMaximum = maximumBytes
            launchOpen(mimeTypes)
        }.let { deferred ->
            try {
                deferred.await()
            } finally {
                pendingOpen = null
                pendingOpenMaximum = MaxInterchangeFileBytes
            }
        }
    }

    override suspend fun save(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        check(pendingOpen == null && pendingSave == null) { "A document picker is already open" }
        require(bytes.size <= MaxInterchangeFileBytes) { "Export files cannot exceed 512 MiB" }
        val deferred = CompletableDeferred<Boolean>()
        pendingSave = PendingSave(bytes, deferred)
        launchSave(filename, mimeType)
        return try {
            deferred.await()
        } finally {
            pendingSave = null
        }
    }

    fun opened(uri: Uri?) {
        val deferred = pendingOpen ?: return
        if (uri == null) {
            deferred.complete(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val filename = queryFilename(uri) ?: "import"
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "Could not open the selected file"
                }.use { readInterchangeBytes(it, pendingOpenMaximum) }
                deferred.complete(InterchangeDocument(filename, bytes))
            } catch (exception: Exception) {
                deferred.completeExceptionally(exception)
            }
        }
    }

    fun saveDestination(uri: Uri?) {
        val save = pendingSave ?: return
        if (uri == null) {
            save.result.complete(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                    "Could not open the export destination"
                }.use { it.write(save.bytes) }
                save.result.complete(true)
            } catch (exception: Exception) {
                save.result.completeExceptionally(exception)
            }
        }
    }

    private fun queryFilename(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}

private data class PendingSave(val bytes: ByteArray, val result: CompletableDeferred<Boolean>)

private fun readInterchangeBytes(input: java.io.InputStream, maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "The selected file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal class AndroidTemporarySqliteFiles(private val context: Context) : TemporarySqliteFiles {
    override fun open(initialBytes: ByteArray?): TemporarySqliteFile {
        val name = "anki-interchange-${randomUuidString()}.sqlite"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        if (initialBytes != null) file.writeBytes(initialBytes)
        return AndroidTemporarySqliteFile(context, name)
    }
}

private class AndroidTemporarySqliteFile(
    private val context: Context,
    private val name: String,
) : TemporarySqliteFile {
    private val file = context.getDatabasePath(name)
    override val driver: SqlDriver = AndroidSqliteDriver(EmptyInterchangeSchema, context, name)
    private var closed = false

    override fun readBytes(): ByteArray {
        check(!closed) { "Temporary database is closed" }
        return file.readBytes()
    }

    override fun close() {
        if (closed) return
        closed = true
        driver.close()
        context.deleteDatabase(name)
    }
}

private object EmptyInterchangeSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: app.cash.sqldelight.db.AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}
