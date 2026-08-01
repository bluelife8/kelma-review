package tech.kelma.app

import com.squareup.zstd.okio.zstdCompress
import com.squareup.zstd.okio.zstdDecompress
import okio.Buffer
import okio.buffer
import okio.use

internal data class AnkiMediaManifestEntry(
    val filename: String,
    val size: Int,
    val sha1: ByteArray,
    val archiveIndex: Int,
)

internal data class ProtoField(
    val number: Int,
    val wireType: Int,
    val varint: Long? = null,
    val bytes: ByteArray? = null,
)

internal fun encodePackageMetadata(version: Int): ByteArray = buildProto {
    varint(1, version.toLong())
}

internal fun encodeMediaManifest(entries: List<AnkiMediaManifestEntry>): ByteArray = buildProto {
    entries.forEach { entry ->
        message(1) {
            string(1, entry.filename)
            varint(2, entry.size.toLong())
            bytes(3, entry.sha1)
        }
    }
}

internal fun decodeMediaManifest(bytes: ByteArray): List<AnkiMediaManifestEntry> =
    decodeProto(bytes).filter { it.number == 1 && it.wireType == 2 }.mapIndexed { index, outer ->
        val fields = decodeProto(outer.bytes ?: ByteArray(0))
        val name = fields.firstOrNull { it.number == 1 && it.wireType == 2 }
            ?.bytes?.decodeToString(throwOnInvalidSequence = true).orEmpty()
        val size = fields.firstOrNull { it.number == 2 && it.wireType == 0 }
            ?.varint?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val hash = fields.firstOrNull { it.number == 3 && it.wireType == 2 }?.bytes ?: ByteArray(0)
        val legacyIndex = fields.firstOrNull { it.number == 255 && it.wireType == 0 }
            ?.varint?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        AnkiMediaManifestEntry(name, size, hash, legacyIndex ?: index)
    }

internal fun decodeProto(bytes: ByteArray): List<ProtoField> {
    val result = mutableListOf<ProtoField>()
    var offset = 0
    while (offset < bytes.size) {
        val key = readVarint(bytes, offset)
        offset = key.nextOffset
        val number = (key.value ushr 3).toInt()
        val wire = (key.value and 7L).toInt()
        require(number > 0) { "Invalid protobuf field number" }
        when (wire) {
            0 -> {
                val value = readVarint(bytes, offset)
                offset = value.nextOffset
                result += ProtoField(number, wire, varint = value.value)
            }
            1 -> {
                require(offset + 8 <= bytes.size) { "Truncated protobuf fixed64 field" }
                result += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, offset + 8))
                offset += 8
            }
            2 -> {
                val length = readVarint(bytes, offset)
                offset = length.nextOffset
                require(length.value in 0..Int.MAX_VALUE.toLong()) { "Invalid protobuf field length" }
                val end = offset + length.value.toInt()
                require(end >= offset && end <= bytes.size) { "Truncated protobuf bytes field" }
                result += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, end))
                offset = end
            }
            5 -> {
                require(offset + 4 <= bytes.size) { "Truncated protobuf fixed32 field" }
                result += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, offset + 4))
                offset += 4
            }
            else -> error("Unsupported protobuf wire type $wire")
        }
    }
    return result
}

internal fun zstdCompress(bytes: ByteArray): ByteArray {
    val output = Buffer()
    output.zstdCompress().buffer().use { compressed -> compressed.write(bytes) }
    return output.readByteArray()
}

internal fun zstdDecompress(
    bytes: ByteArray,
    maximumBytes: Int = MaxInterchangeFileBytes,
): ByteArray {
    val input = Buffer().write(bytes)
    val output = Buffer()
    input.zstdDecompress().buffer().use { decompressed ->
        val chunk = Buffer()
        while (true) {
            val count = decompressed.read(chunk, 64 * 1024L)
            if (count < 0L) break
            output.write(chunk, count)
            require(output.size <= maximumBytes) { "Zstandard payload exceeds the allowed size" }
        }
    }
    return output.readByteArray()
}

internal fun ByteArray.isZstd(): Boolean = size >= 4 &&
    this[0] == 0x28.toByte() && this[1] == 0xB5.toByte() &&
    this[2] == 0x2F.toByte() && this[3] == 0xFD.toByte()

private class ProtoBuilder {
    private val output = MutableByteWriter()

    fun varint(number: Int, value: Long) {
        output.varint((number.toLong() shl 3) or 0L)
        output.varint(value)
    }

    fun string(number: Int, value: String) = bytes(number, value.encodeToByteArray())

    fun bytes(number: Int, value: ByteArray) {
        output.varint((number.toLong() shl 3) or 2L)
        output.varint(value.size.toLong())
        output.write(value)
    }

    fun message(number: Int, block: ProtoBuilder.() -> Unit) = bytes(number, buildProto(block))

    fun toByteArray(): ByteArray = output.toByteArray()
}

private fun buildProto(block: ProtoBuilder.() -> Unit): ByteArray = ProtoBuilder().apply(block).toByteArray()

private data class VarintValue(val value: Long, val nextOffset: Int)

private fun readVarint(bytes: ByteArray, initialOffset: Int): VarintValue {
    var value = 0L
    var shift = 0
    var offset = initialOffset
    while (offset < bytes.size && shift < 64) {
        val current = bytes[offset++].toInt() and 0xff
        value = value or ((current and 0x7f).toLong() shl shift)
        if (current and 0x80 == 0) return VarintValue(value, offset)
        shift += 7
    }
    error("Truncated or oversized protobuf varint")
}

private class MutableByteWriter(initialCapacity: Int = 128) {
    private var bytes = ByteArray(initialCapacity)
    private var size = 0

    fun write(value: ByteArray) {
        ensure(size + value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun varint(input: Long) {
        var value = input
        while (true) {
            if (value and -128L == 0L) {
                byte(value.toInt())
                return
            }
            byte((value.toInt() and 0x7f) or 0x80)
            value = value ushr 7
        }
    }

    private fun byte(value: Int) {
        ensure(size + 1)
        bytes[size++] = value.toByte()
    }

    private fun ensure(required: Int) {
        if (required <= bytes.size) return
        var capacity = bytes.size.coerceAtLeast(1)
        while (capacity < required) capacity = (capacity * 2).coerceAtLeast(required)
        bytes = bytes.copyOf(capacity)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
