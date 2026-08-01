package tech.kelma.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class CredentialVaultPersistenceTest {
    @Test
    fun authenticationTokenSurvivesRestartOnlyThroughTheCredentialVault() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val vault = InMemoryCredentialVault()
        val auth = StoredSyncAuth("secret-token", "client", DefaultKelmaSyncEndpoint, "user@example.com")
        PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault)
            .saveSignedInState(auth, SyncedCollection())

        val restarted = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault).load()
        assertEquals(auth, restarted.auth)
        vault.delete(auth.clientId)
        assertNull(PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault).load().auth)
        driver.close()
    }

    @Test
    fun currentDatabaseSchemaHasNoTokenColumn() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val columns = driver.executeQuery(
            null,
            "PRAGMA table_info(sync_auth)",
            { cursor ->
                QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(1).orEmpty()) })
            },
            0,
        ).value
        assertFalse(columns.any { it.equals("token", ignoreCase = true) })
        driver.close()
    }

    @Test
    fun credentialMigrationPermanentlyRemovesLegacyTokenBytes() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """CREATE TABLE sync_auth (
                singleton_id INTEGER PRIMARY KEY, token TEXT NOT NULL, client_id TEXT NOT NULL,
                endpoint TEXT NOT NULL, username TEXT NOT NULL
            )""".trimIndent(),
            0,
        )
        driver.execute(
            null,
            "INSERT INTO sync_auth VALUES (1, 'legacy-secret', 'client', 'https://sync.invalid', 'user')",
            0,
        )

        KelmaDatabase.Schema.migrate(driver, 13, 14)
        val metadata = KelmaDatabase(driver).kelmaQueries.selectAuth { clientId, endpoint, username ->
            listOf(clientId, endpoint, username)
        }.executeAsOne()

        assertEquals(listOf("client", "https://sync.invalid", "user"), metadata)
        val rawValues = driver.executeQuery(
            null,
            "SELECT client_id, endpoint, username FROM sync_auth",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) {
                            add(cursor.getString(0).orEmpty())
                            add(cursor.getString(1).orEmpty())
                            add(cursor.getString(2).orEmpty())
                        }
                    },
                )
            },
            0,
        ).value
        assertTrue("legacy-secret" !in rawValues)
        driver.close()
    }
}
