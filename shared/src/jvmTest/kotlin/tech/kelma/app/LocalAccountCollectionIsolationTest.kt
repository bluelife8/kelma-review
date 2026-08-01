package tech.kelma.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class LocalAccountCollectionIsolationTest {
    @Test
    fun explicitCredentialRemovalKeepsTheAccountCollection() {
        val directory = createTempDirectory("kelma-sign-out").toFile()
        try {
            val vault = InMemoryCredentialVault()
            openDesktopDatabase(File(directory, "account.db")).use { driver ->
                val store = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault)
                store.saveSignedInState(
                    StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "alice"),
                    SyncedCollection(),
                )
                store.addLocalNote(
                    AddNoteDraft("Alice", "front", "back"),
                    nowMillis = 1_000L,
                    noteGuid = "kept-note",
                )

                store.signOutPreservingCollection()

                assertEquals(null, store.load().auth)
                assertEquals(setOf("kept-note"), store.loadLocalContent().notes.keys)
                assertEquals(null, vault.read("client"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun switchingAwayKeepsTheSavedAccountToken() {
        val directory = createTempDirectory("kelma-token-switch").toFile()
        try {
            val vault = InMemoryCredentialVault()
            val database = File(directory, "account.db")
            openDesktopDatabase(database).use { driver ->
                PersistentCollectionStore(KelmaDatabase(driver), credentialVault = vault).saveSignedInState(
                    StoredSyncAuth("saved-token", "saved-client", DefaultKelmaSyncEndpoint, "alice"),
                    SyncedCollection(),
                )
            }

            assertEquals("saved-token", vault.read("saved-client"))
            openDesktopDatabase(database).use { driver ->
                val restored = PersistentCollectionStore(
                    KelmaDatabase(driver),
                    credentialVault = vault,
                ).load().auth
                assertEquals("saved-token", restored?.token)
                assertEquals("alice", restored?.username)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun eachAccountDatabaseRetainsItsOwnCollection() {
        val directory = createTempDirectory("kelma-accounts").toFile()
        try {
            val aliceFile = File(directory, "alice.db")
            val bobFile = File(directory, "bob.db")
            openDesktopDatabase(aliceFile).use { driver ->
                PersistentCollectionStore(KelmaDatabase(driver)).addLocalNote(
                    AddNoteDraft("Alice", "alice front", "alice back"),
                    nowMillis = 1_000L,
                    noteGuid = "alice-note",
                )
            }
            openDesktopDatabase(bobFile).use { driver ->
                val store = PersistentCollectionStore(KelmaDatabase(driver))
                assertTrue(store.loadLocalContent().notes.isEmpty())
                store.addLocalNote(
                    AddNoteDraft("Bob", "bob front", "bob back"),
                    nowMillis = 2_000L,
                    noteGuid = "bob-note",
                )
            }
            openDesktopDatabase(aliceFile).use { driver ->
                val notes = PersistentCollectionStore(KelmaDatabase(driver)).loadLocalContent().notes
                assertEquals(setOf("alice-note"), notes.keys)
            }
            openDesktopDatabase(bobFile).use { driver ->
                val notes = PersistentCollectionStore(KelmaDatabase(driver)).loadLocalContent().notes
                assertEquals(setOf("bob-note"), notes.keys)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
