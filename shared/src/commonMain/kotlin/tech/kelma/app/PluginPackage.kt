package tech.kelma.app

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

internal const val MaximumPluginPackageBytes: Int = 16 * 1024 * 1024
private const val MaximumPluginFileBytes: Long = 2L * 1024 * 1024
private const val MaximumPluginFiles: Int = 256

internal data class PluginPackage(
    val manifest: PluginManifest,
    val files: Map<String, ByteArray>,
)

internal fun decodePluginPackage(document: InterchangeDocument, json: Json): PluginPackage {
    require(document.bytes.size <= MaximumPluginPackageBytes) { "Plugin packages cannot exceed 16 MiB" }
    require(document.bytes.size >= 4 && document.bytes[0] == 'P'.code.toByte() &&
        document.bytes[1] == 'K'.code.toByte()
    ) { "The selected file is not a .kelmaplugin ZIP package" }
    val archivePath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kelma-plugin-${randomUuidString()}.zip"
    FileSystem.SYSTEM.sink(archivePath).buffer().use { it.write(document.bytes) }
    try {
        FileSystem.SYSTEM.openZip(archivePath).use { archive ->
            val archivePaths = archive.listRecursively("/".toPath()).take(MaximumPluginFiles + 1).toList()
            require(archivePaths.size <= MaximumPluginFiles) { "Plugin packages must contain at most 256 entries" }
            val paths = archivePaths.filter { archive.metadata(it).isRegularFile }
            require(paths.isNotEmpty()) { "Plugin package contains no files" }
            val normalized = paths.map { path -> path.toString().removePrefix("/") }
            require(normalized.distinct().size == normalized.size && normalized.all(::isSafePluginPath)) {
                "Plugin package contains an unsafe or duplicate path"
            }
            val sizes = paths.map { path ->
                archive.metadata(path).size ?: error("Plugin package contains a file with unknown size")
            }
            require(sizes.all { it in 0..MaximumPluginFileBytes }) { "A plugin file exceeds 2 MiB" }
            require(sizes.sum() <= MaximumPluginPackageBytes) { "Expanded plugin package exceeds 16 MiB" }
            val files = paths.indices.associate { index ->
                normalized[index] to archive.source(paths[index]).buffer().use { it.readByteArray() }
            }
            val manifestBytes = requireNotNull(files["manifest.json"]) { "Plugin package is missing manifest.json" }
            val manifest = json.decodeFromString<PluginManifest>(
                manifestBytes.decodeToString(throwOnInvalidSequence = true),
            ).validated()
            require(manifest.entrypoint in files) { "Plugin package entrypoint is missing" }
            files.filterKeys { it.endsWith(".lua") }.forEach { (path, bytes) ->
                require(bytes.isNotEmpty()) { "Lua source is empty: $path" }
                require(bytes.size <= MaximumPluginFileBytes) { "Lua source is too large: $path" }
                require('\u0000' !in bytes.decodeToString(throwOnInvalidSequence = true)) {
                    "Lua source contains a null byte: $path"
                }
            }
            return PluginPackage(manifest, files)
        }
    } catch (failure: IllegalArgumentException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalArgumentException("The selected plugin package is corrupt", failure)
    } finally {
        FileSystem.SYSTEM.delete(archivePath, mustExist = false)
    }
}
