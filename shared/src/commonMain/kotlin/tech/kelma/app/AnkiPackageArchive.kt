package tech.kelma.app

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

internal data class StoredZipEntry(val name: String, val bytes: ByteArray)

internal fun writeStoredZip(entries: List<StoredZipEntry>): ByteArray {
    require(entries.map(StoredZipEntry::name).distinct().size == entries.size) { "ZIP entry names must be unique" }
    val output = Buffer()
    val centralEntries = mutableListOf<CentralZipEntry>()
    entries.forEach { entry ->
        require(entry.name.isNotBlank() && !entry.name.startsWith('/') && ".." !in entry.name.split('/')) {
            "Invalid ZIP entry name"
        }
        val name = entry.name.encodeToByteArray()
        val offset = output.size
        val checksum = crc32(entry.bytes)
        output.writeIntLe(LocalFileHeaderSignature)
        output.writeShortLe(20)
        output.writeShortLe(Utf8Flag)
        output.writeShortLe(StoredMethod)
        output.writeShortLe(0)
        output.writeShortLe(DosDate1980)
        output.writeIntLe(checksum)
        output.writeIntLe(entry.bytes.size)
        output.writeIntLe(entry.bytes.size)
        output.writeShortLe(name.size)
        output.writeShortLe(0)
        output.write(name)
        output.write(entry.bytes)
        centralEntries += CentralZipEntry(name, checksum, entry.bytes.size, offset)
    }
    val centralOffset = output.size
    centralEntries.forEach { entry ->
        output.writeIntLe(CentralFileHeaderSignature)
        output.writeShortLe(20)
        output.writeShortLe(20)
        output.writeShortLe(Utf8Flag)
        output.writeShortLe(StoredMethod)
        output.writeShortLe(0)
        output.writeShortLe(DosDate1980)
        output.writeIntLe(entry.crc32)
        output.writeIntLe(entry.size)
        output.writeIntLe(entry.size)
        output.writeShortLe(entry.name.size)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeIntLe(0)
        output.writeIntLe(entry.offset.requireZip32("ZIP entry offset"))
        output.write(entry.name)
    }
    val centralSize = output.size - centralOffset
    require(centralEntries.size <= 65_535) { "Anki packages cannot contain more than 65,535 files" }
    output.writeIntLe(EndOfCentralDirectorySignature)
    output.writeShortLe(0)
    output.writeShortLe(0)
    output.writeShortLe(centralEntries.size)
    output.writeShortLe(centralEntries.size)
    output.writeIntLe(centralSize.requireZip32("ZIP central directory size"))
    output.writeIntLe(centralOffset.requireZip32("ZIP central directory offset"))
    output.writeShortLe(0)
    return output.readByteArray()
}

internal class AnkiPackageArchive private constructor(
    private val archivePath: Path,
    private val zip: FileSystem,
) : AutoCloseable {
    fun contains(name: String): Boolean = zip.exists("/$name".toPath())

    fun read(name: String, maximumBytes: Int = MaxInterchangeFileBytes): ByteArray {
        val path = "/$name".toPath()
        require(zip.exists(path)) { "Anki package entry is missing: $name" }
        val size = zip.metadata(path).size
        require(size != null && size <= maximumBytes) { "Anki package entry is too large: $name" }
        return zip.source(path).buffer().use { source ->
            source.readByteArray().also { bytes ->
                require(bytes.size <= maximumBytes) { "Anki package entry is too large: $name" }
            }
        }
    }

    override fun close() {
        FileSystem.SYSTEM.delete(archivePath, mustExist = false)
    }

    companion object {
        fun open(bytes: ByteArray): AnkiPackageArchive {
            require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                "The selected file is not a ZIP package"
            }
            val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kelma-anki-${randomUuidString()}.zip"
            FileSystem.SYSTEM.sink(path).buffer().use { it.write(bytes) }
            return try {
                AnkiPackageArchive(path, FileSystem.SYSTEM.openZip(path))
            } catch (failure: Throwable) {
                FileSystem.SYSTEM.delete(path, mustExist = false)
                throw IllegalArgumentException("The selected package is corrupt", failure)
            }
        }
    }
}

private data class CentralZipEntry(
    val name: ByteArray,
    val crc32: Int,
    val size: Int,
    val offset: Long,
)

private fun Long.requireZip32(label: String): Int {
    require(this in 0..0xffff_ffffL) { "$label exceeds the ZIP32 limit" }
    return toInt()
}

private fun crc32(bytes: ByteArray): Int {
    var crc = -1
    bytes.forEach { byte ->
        crc = crc xor (byte.toInt() and 0xff)
        repeat(8) { crc = (crc ushr 1) xor if (crc and 1 != 0) CrcPolynomial else 0 }
    }
    return crc.inv()
}

private const val LocalFileHeaderSignature = 0x04034b50
private const val CentralFileHeaderSignature = 0x02014b50
private const val EndOfCentralDirectorySignature = 0x06054b50
private const val StoredMethod = 0
private const val Utf8Flag = 0x0800
private const val DosDate1980 = 0x0021
private const val CrcPolynomial = -306_674_912
