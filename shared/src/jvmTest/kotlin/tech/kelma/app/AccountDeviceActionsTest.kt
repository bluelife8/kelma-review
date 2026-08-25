package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import tech.kelma.db.KelmaDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountDeviceActionsTest {
    @Test
    fun signOutRemovesTheTokenButPreservesTheLocalCollectionAndRegistry() = runBlocking {
        val fixture = accountFixture()
        var working = false
        var leftAccount = false
        val actions = accountDeviceActions(
            accountRegistry = fixture.registry,
            store = fixture.store,
            luaPluginHost = fixture.pluginHost,
            scope = this,
            isWorking = { working },
            isRestored = { true },
            setWorking = { working = it },
            setError = { error(it) },
            setPluginHostState = {},
            leaveAccount = { leftAccount = true },
        )

        actions.signOut()
        coroutineContext[Job]?.children?.toList().orEmpty().joinAll()

        assertFalse(working)
        assertTrue(leftAccount)
        assertEquals("person@example.com", fixture.registry.activeAccount()?.username)
        assertNull(fixture.vault.read("client"))
        assertTrue(fixture.store.loadLocalContent().notes.containsKey("local-account-action-test"))
        assertTrue(fixture.mediaCache.read("image.jpg")?.isNotEmpty() == true)
        fixture.close()
    }

    @Test
    fun removeFromDeviceClearsLocalDataCredentialsAndRegistry() = runBlocking {
        val fixture = accountFixture()
        var working = false
        var leftAccount = false
        var pluginState: PluginHostState? = null
        val actions = accountDeviceActions(
            accountRegistry = fixture.registry,
            store = fixture.store,
            luaPluginHost = fixture.pluginHost,
            scope = this,
            isWorking = { working },
            isRestored = { true },
            setWorking = { working = it },
            setError = { error(it) },
            setPluginHostState = { pluginState = it },
            leaveAccount = { leftAccount = true },
        )

        actions.removeFromDevice()
        coroutineContext[Job]?.children?.toList().orEmpty().joinAll()

        assertFalse(working)
        assertTrue(leftAccount)
        assertNull(fixture.registry.activeAccount())
        assertTrue(fixture.registry.accounts().isEmpty())
        assertNull(fixture.vault.read("client"))
        assertTrue(fixture.store.loadLocalContent().notes.isEmpty())
        assertTrue(fixture.store.listInstalledPlugins().isEmpty())
        assertNull(fixture.mediaCache.read("image.jpg"))
        assertTrue(pluginState?.running.orEmpty().isEmpty())
        fixture.close()
    }
}

private data class AccountActionFixture(
    val driver: JdbcSqliteDriver,
    val vault: InMemoryCredentialVault,
    val registry: LocalAccountRegistry,
    val mediaCache: AccountActionMediaCache,
    val store: PersistentCollectionStore,
    val pluginHost: LuaPluginHost,
) {
    fun close() {
        pluginHost.close()
        driver.close()
    }
}

private fun accountFixture(): AccountActionFixture {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    KelmaDatabase.Schema.create(driver)
    val vault = InMemoryCredentialVault()
    val mediaCache = AccountActionMediaCache()
    val store = PersistentCollectionStore(
        KelmaDatabase(driver),
        credentialVault = vault,
        mediaCache = mediaCache,
    )
    store.saveSignedInState(
        StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "person@example.com"),
        SyncedCollection(
            media = mapOf("image.jpg" to SyncMediaFile("image.jpg", "v1", byteArrayOf(1, 2, 3))),
        ),
    )
    store.addLocalNote(
        AddNoteDraft("Local", "front", "back"),
        noteGuid = "local-account-action-test",
    )
    store.installPluginManifest(
        PluginManifest(
            id = "tech.kelma.account-action-test",
            name = "Account Action Test",
            version = "1.0.0",
            apiVersion = KelmaPluginApiVersion,
            entrypoint = "plugin/test.lua",
        ),
    )
    val registry = LocalAccountRegistry(AccountActionRegistryStorage()).apply {
        activate(DefaultKelmaSyncEndpoint, "person@example.com")
    }
    val pluginHost = store.createLuaPluginHost(
        PluginCommandRegistry(),
        PluginEventRegistry(),
        PluginRendererRegistry(),
    )
    return AccountActionFixture(driver, vault, registry, mediaCache, store, pluginHost)
}

private class AccountActionMediaCache : MediaCache {
    private val files = mutableMapOf<String, ByteArray>()

    override fun read(filename: String): ByteArray? = files[filename]

    override fun write(filename: String, bytes: ByteArray) {
        files[filename] = bytes
    }

    override fun delete(filename: String) {
        files.remove(filename)
    }

    override fun clear() {
        files.clear()
    }
}

private class AccountActionRegistryStorage : LocalAccountRegistryStorage {
    private var value: String? = null

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }
}
