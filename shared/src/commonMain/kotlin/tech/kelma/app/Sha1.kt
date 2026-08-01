package tech.kelma.app

internal fun sha1(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8L
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()
    val words = IntArray(80)
    for (offset in padded.indices step 64) {
        for (index in 0 until 16) {
            val position = offset + index * 4
            words[index] = (padded[position].toInt() and 0xff shl 24) or
                (padded[position + 1].toInt() and 0xff shl 16) or
                (padded[position + 2].toInt() and 0xff shl 8) or
                (padded[position + 3].toInt() and 0xff)
        }
        for (index in 16 until 80) {
            words[index] = (words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16])
                .rotateLeft(1)
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (index in 0 until 80) {
            val (function, constant) = when (index) {
                in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                else -> (b xor c xor d) to 0xCA62C1D6.toInt()
            }
            val next = a.rotateLeft(5) + function + e + constant + words[index]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = next
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }
    return ByteArray(20).also { output ->
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { wordIndex, value ->
            repeat(4) { byteIndex ->
                output[wordIndex * 4 + byteIndex] = (value ushr (24 - byteIndex * 8)).toByte()
            }
        }
    }
}

internal fun ByteArray.hexString(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
