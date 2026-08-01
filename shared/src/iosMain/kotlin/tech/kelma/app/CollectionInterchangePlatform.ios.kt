package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove

@Composable
actual fun rememberCollectionInterchangePlatform(): CollectionInterchangePlatform {
    val presenter = LocalUIViewController.current
    return remember(presenter) {
        CollectionInterchangePlatform(IosCollectionDocumentIO(presenter), IosTemporarySqliteFiles())
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosCollectionDocumentIO(private val presenter: UIViewController) : CollectionDocumentIO {
    private var pendingOpen: CompletableDeferred<InterchangeDocument?>? = null
    private var pendingOpenMaximum = MaxInterchangeFileBytes
    private var pendingSave: CompletableDeferred<Boolean>? = null
    private var exportTempPath: String? = null
    private val delegate = InterchangeDocumentPickerDelegate(::selected, ::cancelled)

    override suspend fun open(): InterchangeDocument? = openDocument(MaxInterchangeFileBytes)

    override suspend fun openPlugin(): InterchangeDocument? = openDocument(MaximumPluginPackageBytes)

    private suspend fun openDocument(maximumBytes: Int): InterchangeDocument? {
        check(pendingOpen == null && pendingSave == null) { "A document picker is already open" }
        val deferred = CompletableDeferred<InterchangeDocument?>()
        pendingOpen = deferred
        pendingOpenMaximum = maximumBytes
        presenter.presentViewController(
            UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true).apply {
                delegate = this@IosCollectionDocumentIO.delegate
            },
            animated = true,
            completion = null,
        )
        return try {
            deferred.await()
        } finally {
            pendingOpen = null
            pendingOpenMaximum = MaxInterchangeFileBytes
        }
    }

    override suspend fun save(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        check(pendingOpen == null && pendingSave == null) { "A document picker is already open" }
        require(bytes.size <= MaxInterchangeFileBytes) { "Export files cannot exceed 512 MiB" }
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "export" }
        val path = "${NSTemporaryDirectory()}$safeName"
        writeNativeFile(path, bytes)
        exportTempPath = path
        val deferred = CompletableDeferred<Boolean>()
        pendingSave = deferred
        val url = NSURL.fileURLWithPath(path)
        presenter.presentViewController(
            UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true).apply {
                delegate = this@IosCollectionDocumentIO.delegate
            },
            animated = true,
            completion = null,
        )
        return try {
            deferred.await()
        } finally {
            pendingSave = null
            exportTempPath?.let { remove(it) }
            exportTempPath = null
        }
    }

    private fun selected(url: NSURL?) {
        pendingSave?.let {
            it.complete(url != null)
            return
        }
        val deferred = pendingOpen ?: return
        if (url == null) {
            deferred.complete(null)
            return
        }
        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val path = checkNotNull(url.path) { "Selected file has no path" }
            val bytes = readNativeFile(path, pendingOpenMaximum)
            deferred.complete(InterchangeDocument(url.lastPathComponent ?: "import", bytes))
        } catch (exception: Exception) {
            deferred.completeExceptionally(exception)
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun cancelled() {
        pendingOpen?.complete(null)
        pendingSave?.complete(false)
    }
}

private class InterchangeDocumentPickerDelegate(
    private val onSelected: (NSURL?) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onSelected(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onCancelled()
}

@OptIn(ExperimentalForeignApi::class)
internal class IosTemporarySqliteFiles : TemporarySqliteFiles {
    override fun open(initialBytes: ByteArray?): TemporarySqliteFile {
        val name = "anki-interchange-${randomUuidString()}.sqlite"
        val directory = NSTemporaryDirectory().removeSuffix("/")
        val path = "$directory/$name"
        if (initialBytes != null) writeNativeFile(path, initialBytes)
        return IosTemporarySqliteFile(name, directory, path)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosTemporarySqliteFile(
    name: String,
    directory: String,
    private val path: String,
) : TemporarySqliteFile {
    override val driver: SqlDriver = NativeSqliteDriver(
        schema = EmptyInterchangeSchema,
        name = name,
        onConfiguration = { configuration ->
            configuration.copy(
                journalMode = JournalMode.DELETE,
                extendedConfig = configuration.extendedConfig.copy(basePath = directory),
            )
        },
    )
    private var closed = false

    override fun readBytes(): ByteArray {
        check(!closed) { "Temporary database is closed" }
        return readNativeFile(path, MaxInterchangeFileBytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        driver.close()
        remove(path)
        remove("$path-wal")
        remove("$path-shm")
    }
}

private object EmptyInterchangeSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}

@OptIn(ExperimentalForeignApi::class)
private fun readNativeFile(path: String, maximumBytes: Int): ByteArray {
    val file = checkNotNull(fopen(path, "rb")) { "Could not open the selected file" }
    return try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        check(size >= 0) { "Could not measure the selected file" }
        require(size <= maximumBytes) { "Import files cannot exceed 512 MiB" }
        fseek(file, 0, SEEK_SET)
        if (size == 0L) return ByteArray(0)
        ByteArray(size.toInt()).also { bytes ->
            bytes.usePinned { pinned ->
                val count = fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                check(count.toLong() == bytes.size.toLong()) { "Could not read the selected file" }
            }
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeNativeFile(path: String, bytes: ByteArray) {
    val file = checkNotNull(fopen(path, "wb")) { "Could not create the export file" }
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val count = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                check(count.toLong() == bytes.size.toLong()) { "Could not write the export file" }
            }
        }
    } finally {
        fclose(file)
    }
}
