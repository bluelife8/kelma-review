package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocalAccountRegistryTest {
    @Test
    fun accountsKeepStableCollectionDatabasesAcrossSignOutAndSwitching() {
        val storage = MemoryAccountRegistryStorage()
        val registry = LocalAccountRegistry(storage)

        assertEquals(LegacyCollectionDatabaseName, registry.activeDatabaseName())
        val first = registry.activate("https://sync2.kelma.tech", "alice")
        registry.deactivate()
        assertEquals(GuestCollectionDatabaseName, registry.activeDatabaseName())
        val second = registry.activate("https://sync2.kelma.tech", "bob")
        assertNotEquals(first, second)
        assertEquals(first, registry.activate("https://sync2.kelma.tech/", "ALICE"))

        val restored = LocalAccountRegistry(storage)
        assertEquals(first, restored.activeDatabaseName())
        assertEquals(2, restored.accounts().size)
    }

    @Test
    fun legacyCollectionCanBeRegisteredWithoutMovingItsDatabase() {
        val storage = MemoryAccountRegistryStorage()
        val registry = LocalAccountRegistry(storage)

        registry.registerCurrent(
            "https://sync2.kelma.tech",
            "legacy-user",
            LegacyCollectionDatabaseName,
        )
        registry.deactivate()

        assertEquals(
            LegacyCollectionDatabaseName,
            registry.activate("https://sync2.kelma.tech", "legacy-user"),
        )
    }
}

private class MemoryAccountRegistryStorage : LocalAccountRegistryStorage {
    private var value: String? = null

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}
