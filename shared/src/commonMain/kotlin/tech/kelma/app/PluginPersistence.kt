package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

internal class PluginPersistence(
    private val database: KelmaDatabase,
    private val json: Json,
) {
    private val queries = database.kelmaQueries

    fun list(): List<InstalledPlugin> = queries.selectPluginInstallations {
            _, manifestJson, enabled, status, error, installedAt, updatedAt ->
        InstalledPlugin(
            manifest = json.decodeFromString<PluginManifest>(manifestJson).validated(),
            enabled = enabled == 1L,
            status = PluginStatus.entries.first { it.name.equals(status, ignoreCase = true) },
            errorMessage = error,
            installedAtMillis = installedAt,
            updatedAtMillis = updatedAt,
        )
    }.executeAsList()

    fun install(manifest: PluginManifest, nowMillis: Long): InstalledPlugin =
        saveInstallation(manifest.validated(), nowMillis)

    fun install(pluginPackage: PluginPackage, nowMillis: Long): InstalledPlugin {
        val validated = pluginPackage.manifest.validated()
        database.transaction {
            saveInstallation(validated, nowMillis)
            queries.clearPluginFiles(validated.id)
            pluginPackage.files.entries.sortedBy { it.key }.forEach { (path, content) ->
                queries.insertPluginFile(validated.id, path, content)
            }
        }
        return list().first { it.manifest.id == validated.id }
    }

    private fun saveInstallation(manifest: PluginManifest, nowMillis: Long): InstalledPlugin {
        val existing = list().firstOrNull { it.manifest.id == manifest.id }
        val manifestJson = json.encodeToString(manifest)
        if (existing == null) {
            queries.insertPluginInstallation(
                pluginId = manifest.id,
                manifestJson = manifestJson,
                enabled = 1L,
                status = PluginStatus.Installed.name.lowercase(),
                errorMessage = null,
                installedAt = nowMillis,
                updatedAt = nowMillis,
            )
        } else {
            queries.updatePluginInstallation(
                manifestJson = manifestJson,
                enabled = 1L,
                status = PluginStatus.Installed.name.lowercase(),
                errorMessage = null,
                updatedAt = nowMillis,
                pluginId = manifest.id,
            )
        }
        return list().first { it.manifest.id == manifest.id }
    }

    fun files(pluginId: String): Map<String, ByteArray> = queries.selectPluginFiles(pluginId) { path, content ->
        path to content
    }.executeAsList().toMap()

    fun setEnabled(pluginId: String, enabled: Boolean, nowMillis: Long): List<InstalledPlugin> {
        require(list().any { it.manifest.id == pluginId }) { "Plugin is not installed" }
        queries.setPluginEnabled(
            enabled = if (enabled) 1L else 0L,
            status = if (enabled) PluginStatus.Installed.name.lowercase() else PluginStatus.Disabled.name.lowercase(),
            updatedAt = nowMillis,
            pluginId = pluginId,
        )
        return list()
    }

    fun setStatus(pluginId: String, status: PluginStatus, error: String?, nowMillis: Long) {
        queries.setPluginStatus(status.name.lowercase(), error?.take(1_000), nowMillis, pluginId)
    }

    fun appendLogs(pluginId: String, logs: List<PluginRuntimeLog>, nowMillis: Long) {
        if (logs.isEmpty()) return
        database.transaction {
            logs.forEach { log -> queries.insertPluginLog(pluginId, nowMillis, log.level, log.message.take(2_048)) }
            queries.prunePluginLogs(pluginId, 2_000)
        }
    }

    fun logs(pluginId: String, limit: Long = 200): List<PluginLogEntry> =
        queries.selectPluginLogs(pluginId, limit) { id, idPlugin, occurredAt, level, message ->
            PluginLogEntry(id, idPlugin, occurredAt, level, message)
        }.executeAsList()

    fun safeMode(): Boolean = queries.selectPluginSafeMode().executeAsOneOrNull() == 1L

    fun setSafeMode(enabled: Boolean) {
        queries.setPluginSafeMode(if (enabled) 1L else 0L)
    }

    fun uninstall(pluginId: String) {
        database.transaction {
            delete(pluginId)
        }
    }

    fun clearAll() {
        queries.selectPluginIds().executeAsList().forEach(::delete)
        queries.setPluginSafeMode(0)
    }

    private fun delete(pluginId: String) {
        queries.clearPluginLogs(pluginId)
        queries.clearPluginFiles(pluginId)
        queries.clearPluginSettings(pluginId)
        queries.deletePluginInstallation(pluginId)
    }
}
