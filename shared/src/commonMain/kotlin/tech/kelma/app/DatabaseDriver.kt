package tech.kelma.app

import app.cash.sqldelight.db.SqlDriver
import androidx.compose.runtime.Composable

@Composable
expect fun rememberDatabaseDriver(databaseName: String): SqlDriver
