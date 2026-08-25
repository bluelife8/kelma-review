package tech.kelma.app

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val LegacyCollectionDatabaseName = "kelma.db"
internal const val GuestCollectionDatabaseName = "kelma-guest.db"

@Serializable
internal data class LocalAccountRecord(
    val key: String,
    val endpoint: String,
    val username: String,
    val databaseName: String,
)

@Serializable
private data class LocalAccountRegistryState(
    val initialized: Boolean = false,
    val activeKey: String? = null,
    val accounts: List<LocalAccountRecord> = emptyList(),
)

internal interface LocalAccountRegistryStorage {
    fun read(): String?
    fun write(value: String)
}

@Composable
internal expect fun rememberLocalAccountRegistryStorage(): LocalAccountRegistryStorage

internal class LocalAccountRegistry(
    private val storage: LocalAccountRegistryStorage,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var state = storage.read()?.let { encoded ->
        runCatching { json.decodeFromString<LocalAccountRegistryState>(encoded) }.getOrNull()
    } ?: LocalAccountRegistryState()

    fun activeDatabaseName(): String = when {
        !state.initialized -> LegacyCollectionDatabaseName
        state.activeKey == null -> GuestCollectionDatabaseName
        else -> state.accounts.firstOrNull { it.key == state.activeKey }?.databaseName
            ?: GuestCollectionDatabaseName
    }

    fun registerCurrent(endpoint: String, username: String, databaseName: String) {
        val normalizedEndpoint = endpoint.trimEnd('/')
        val normalizedUsername = username.trim()
        val key = accountKey(normalizedEndpoint, normalizedUsername)
        val record = LocalAccountRecord(key, normalizedEndpoint, normalizedUsername, databaseName)
        state = state.copy(
            initialized = true,
            activeKey = key,
            accounts = state.accounts.filterNot { it.key == key } + record,
        )
        persist()
    }

    fun databaseName(endpoint: String, username: String): String? {
        val key = accountKey(endpoint, username)
        return state.accounts.firstOrNull { it.key == key }?.databaseName
    }

    fun activate(endpoint: String, username: String): String {
        val normalizedEndpoint = endpoint.trimEnd('/')
        val normalizedUsername = username.trim()
        val key = accountKey(normalizedEndpoint, normalizedUsername)
        val existing = state.accounts.firstOrNull { it.key == key }
        val databaseName = existing?.databaseName ?: "kelma-account-${key.take(24)}.db"
        registerCurrent(normalizedEndpoint, normalizedUsername, databaseName)
        return databaseName
    }

    fun deactivate() {
        state = state.copy(initialized = true, activeKey = null)
        persist()
    }

    fun activeAccount(): LocalAccountRecord? = state.accounts.firstOrNull { it.key == state.activeKey }

    fun remove(endpoint: String, username: String): LocalAccountRecord? {
        val key = accountKey(endpoint, username)
        val removed = state.accounts.firstOrNull { it.key == key } ?: return null
        state = state.copy(
            initialized = true,
            activeKey = state.activeKey.takeUnless { it == key },
            accounts = state.accounts.filterNot { it.key == key },
        )
        persist()
        return removed
    }

    internal fun accounts(): List<LocalAccountRecord> = state.accounts

    private fun persist() {
        storage.write(json.encodeToString(state))
    }
}

internal fun accountKey(endpoint: String, username: String): String =
    SchedulerHistorySha256()
        .update(endpoint.trimEnd('/').lowercase())
        .update("\u0000")
        .update(username.trim().lowercase())
        .hexDigest()
