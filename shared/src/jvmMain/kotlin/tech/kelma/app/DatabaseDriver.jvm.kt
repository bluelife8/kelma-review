package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.sql.DriverManager
import tech.kelma.db.KelmaDatabase

@Composable
actual fun rememberDatabaseDriver(databaseName: String): SqlDriver = remember(databaseName) {
    openDesktopDatabase(File(desktopDataDirectory(), databaseName))
}

internal fun openDesktopDatabase(databaseFile: File): SqlDriver {
    val existed = databaseFile.exists()
    databaseFile.parentFile?.mkdirs()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePath}"
    val storedVersion = if (existed) readDatabaseVersion(jdbcUrl) else 0L
    return JdbcSqliteDriver(jdbcUrl).also { driver ->
        if (!existed || storedVersion == 0L && !databaseHasTable(jdbcUrl, "sync_auth")) {
            KelmaDatabase.Schema.create(driver)
        } else {
            val sourceVersion = if (storedVersion == 0L) 1L else storedVersion
            require(sourceVersion <= KelmaDatabase.Schema.version) {
                "The Kelma database was created by a newer app version"
            }
            if (sourceVersion < KelmaDatabase.Schema.version) {
                KelmaDatabase.Schema.migrate(driver, sourceVersion, KelmaDatabase.Schema.version)
            }
        }
        driver.execute(
            identifier = null,
            sql = "PRAGMA user_version = ${KelmaDatabase.Schema.version}",
            parameters = 0,
        )
    }
}

private fun readDatabaseVersion(jdbcUrl: String): Long =
    DriverManager.getConnection(jdbcUrl).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                if (result.next()) result.getLong(1) else 0L
            }
        }
    }

private fun databaseHasTable(jdbcUrl: String, tableName: String): Boolean =
    DriverManager.getConnection(jdbcUrl).use { connection ->
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.setString(1, tableName)
            statement.executeQuery().use { it.next() }
        }
    }

internal fun desktopDataDirectory(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> File(home, "Library/Application Support/Kelma")
        os.contains("win") -> File(System.getenv("APPDATA") ?: home, "Kelma")
        else -> File(home, ".local/share/kelma")
    }
}
