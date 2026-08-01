package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import tech.kelma.db.KelmaDatabase

@Composable
actual fun rememberDatabaseDriver(databaseName: String): SqlDriver = remember(databaseName) {
    NativeSqliteDriver(KelmaDatabase.Schema, databaseName)
}
