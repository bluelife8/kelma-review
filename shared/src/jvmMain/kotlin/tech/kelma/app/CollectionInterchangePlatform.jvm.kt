package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
actual fun rememberCollectionInterchangePlatform(): CollectionInterchangePlatform = remember {
    CollectionInterchangePlatform(JvmCollectionDocumentIO(), JvmTemporarySqliteFiles())
}

internal class JvmCollectionDocumentIO : CollectionDocumentIO {
    override suspend fun open(): InterchangeDocument? = openDocument(
        title = "Import File",
        extensions = setOf("apkg", "colpkg", "json", "txt", "csv", "tsv"),
        maximumBytes = MaxInterchangeFileBytes,
    )

    override suspend fun openPlugin(): InterchangeDocument? = openDocument(
        title = "Install Plugin",
        extensions = setOf("kelmaplugin"),
        maximumBytes = MaximumPluginPackageBytes,
    )

    private suspend fun openDocument(
        title: String,
        extensions: Set<String>,
        maximumBytes: Int,
    ): InterchangeDocument? {
        val selected = chooseFile(
            title = title,
            mode = FileDialog.LOAD,
            filter = { name -> name.substringAfterLast('.', "").lowercase() in extensions },
        ) ?: return null
        return withContext(Dispatchers.IO) {
            require(selected.length() <= maximumBytes) { "The selected file is too large" }
            InterchangeDocument(selected.name, selected.readBytes())
        }
    }

    override suspend fun save(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        require(bytes.size <= MaxInterchangeFileBytes) { "Export files cannot exceed 512 MiB" }
        val selected = chooseFile("Export", FileDialog.SAVE, suggestedName = filename) ?: return false
        withContext(Dispatchers.IO) { selected.writeBytes(bytes) }
        return true
    }

    private suspend fun chooseFile(
        title: String,
        mode: Int,
        filter: ((String) -> Boolean)? = null,
        suggestedName: String? = null,
    ): File? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            val dialog = FileDialog(null as Frame?, title, mode).apply {
                file = suggestedName
                if (filter != null) filenameFilter = java.io.FilenameFilter { _, name -> filter(name) }
                isVisible = true
            }
            val selected = dialog.file?.let { File(dialog.directory, it) }
            dialog.dispose()
            if (continuation.isActive) continuation.resume(selected)
        }
    }
}

internal class JvmTemporarySqliteFiles : TemporarySqliteFiles {
    override fun open(initialBytes: ByteArray?): TemporarySqliteFile {
        val path = Files.createTempFile("kelma-anki-", ".sqlite")
        if (initialBytes != null) Files.write(path, initialBytes)
        return JvmTemporarySqliteFile(path)
    }
}

private class JvmTemporarySqliteFile(private val path: Path) : TemporarySqliteFile {
    override val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").also {
        it.execute(null, "PRAGMA journal_mode = DELETE", 0)
    }
    private var closed = false

    override fun readBytes(): ByteArray {
        check(!closed) { "Temporary database is closed" }
        driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
        return Files.readAllBytes(path)
    }

    override fun close() {
        if (closed) return
        closed = true
        driver.close()
        Files.deleteIfExists(path)
        Files.deleteIfExists(Path.of("$path-wal"))
        Files.deleteIfExists(Path.of("$path-shm"))
    }
}
