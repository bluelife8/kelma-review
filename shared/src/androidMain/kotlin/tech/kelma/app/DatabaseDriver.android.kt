package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import tech.kelma.db.KelmaDatabase

@Composable
actual fun rememberDatabaseDriver(databaseName: String): SqlDriver {
    val context = LocalContext.current.applicationContext
    return remember(context, databaseName) {
        AndroidSqliteDriver(
            schema = KelmaDatabase.Schema,
            context = context,
            name = databaseName,
        )
    }
}
