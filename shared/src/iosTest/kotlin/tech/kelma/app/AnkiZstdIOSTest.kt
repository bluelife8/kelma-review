package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertTrue

class AnkiZstdIOSTest {
    @Test
    fun zstandardRoundTripsOnNativeRuntime() {
        val payload = "portable zstandard payload".repeat(100).encodeToByteArray()
        assertTrue(zstdDecompress(zstdCompress(payload)).contentEquals(payload))
    }
}
