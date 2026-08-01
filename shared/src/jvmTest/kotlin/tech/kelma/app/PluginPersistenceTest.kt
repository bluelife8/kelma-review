package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class PluginPersistenceTest {
    @Test
    fun installationEnablementAndUninstallSurviveStoreRestart() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        var store = PersistentCollectionStore(database)
        val manifest = PluginManifest(
            id = "tech.kelma.sample",
            name = "Sample",
            version = "1.0.0",
            apiVersion = KelmaPluginApiVersion,
            entrypoint = "plugin/sample.lua",
            capabilities = setOf(PluginCapability.Commands, PluginCapability.Events),
        )

        store.installPluginManifest(manifest, 100L)
        database.kelmaQueries.upsertPluginSetting(manifest.id, "choice", "\"kept\"", 150L)
        store.installPluginManifest(manifest.copy(version = "1.1.0"), 175L)
        assertEquals("\"kept\"", database.kelmaQueries.selectPluginSetting(manifest.id, "choice").executeAsOne())
        store.setPluginEnabled(manifest.id, false, 200L)
        store = PersistentCollectionStore(database)
        val restored = store.listInstalledPlugins().single()
        assertEquals(manifest.copy(version = "1.1.0"), restored.manifest)
        assertFalse(restored.enabled)
        assertEquals(PluginStatus.Disabled, restored.status)
        assertEquals(100L, restored.installedAtMillis)

        store.setPluginEnabled(manifest.id, true, 300L)
        assertTrue(store.listInstalledPlugins().single().enabled)
        store.uninstallPlugin(manifest.id)
        assertTrue(store.listInstalledPlugins().isEmpty())
        driver.close()
    }
}
