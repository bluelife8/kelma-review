package tech.kelma.app

import kotlinx.serialization.Serializable

/** Current additive plugin API generation understood by this client. */
const val KelmaPluginApiVersion: Int = 1

/** Capabilities a plugin must declare before the host exposes the corresponding service. */
@Serializable
enum class PluginCapability {
    Commands,
    Events,
    NotesRead,
    NotesWrite,
    Network,
    Files,
    Secrets,
    Workers,
    Ui,
}

/** A dependency on another installed plugin and its minimum semantic version. */
@Serializable
data class PluginDependency(
    val id: String,
    val minimumVersion: String,
    val optional: Boolean = false,
)

/** Stable, serializable manifest stored beside a portable Lua 5.4 plugin package. */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val entrypoint: String,
    val runtime: String = "lua54",
    val capabilities: Set<PluginCapability> = emptySet(),
    val dependencies: List<PluginDependency> = emptyList(),
) {
    fun validated(): PluginManifest {
        require(PluginId.matches(id)) { "Plugin id must use reverse-domain components" }
        require(name.isNotBlank() && name.length <= 100) { "Plugin name is invalid" }
        SemanticVersion.parse(version)
        require(apiVersion == KelmaPluginApiVersion) { "Plugin API $apiVersion is not supported" }
        require(runtime == "lua54") { "Only the portable Lua 5.4 runtime is supported" }
        require(entrypoint.endsWith(".lua") && isSafePluginPath(entrypoint)) { "Plugin entrypoint is invalid" }
        require(dependencies.map(PluginDependency::id).distinct().size == dependencies.size) {
            "Plugin dependencies must be unique"
        }
        dependencies.forEach {
            require(PluginId.matches(it.id) && it.id != id) { "Plugin dependency id is invalid" }
            SemanticVersion.parse(it.minimumVersion)
        }
        return this
    }
}

/** Persisted installation metadata; plugin source remains in application-owned package storage. */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val status: PluginStatus,
    val errorMessage: String?,
    val installedAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Durable lifecycle state shown by the plugin manager. */
enum class PluginStatus { Installed, Disabled, Blocked, Failed }

/** Content-free diagnostic emitted by a plugin or its host. */
data class PluginLogEntry(
    val id: Long,
    val pluginId: String,
    val occurredAtMillis: Long,
    val level: String,
    val message: String,
)

internal data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    companion object {
        fun parse(value: String): SemanticVersion {
            val match = SemanticVersionPattern.matchEntire(value)
                ?: throw IllegalArgumentException("Plugin version must be semantic major.minor.patch")
            return SemanticVersion(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
    }
}

private val PluginId = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")
private val SemanticVersionPattern = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")
internal fun isSafePluginPath(value: String): Boolean =
    value.isNotBlank() && value.length <= 240 &&
        value.all { (it.isLetterOrDigit() && it.code < 128) || it in "._-/" } &&
        !value.startsWith('/') && value.split('/').none { it in setOf("", ".", "..") }
