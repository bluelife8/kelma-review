package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnkiWireFormatTest {
    @Test
    fun sha1AndPackageMetadataMatchKnownVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).hexString())
        assertTrue(encodePackageMetadata(3).contentEquals(byteArrayOf(0x08, 0x03)))
    }

    @Test
    fun mediaManifestAndZipRoundTrip() {
        val payload = "portable package payload".repeat(100).encodeToByteArray()
        val expected = AnkiMediaManifestEntry("asset.txt", payload.size, sha1(payload), 0)
        val manifest = decodeMediaManifest(encodeMediaManifest(listOf(expected))).single()
        assertEquals(expected.filename, manifest.filename)
        assertEquals(expected.size, manifest.size)
        assertTrue(expected.sha1.contentEquals(manifest.sha1))

        val zip = writeStoredZip(
            listOf(
                StoredZipEntry("meta", encodePackageMetadata(3)),
                StoredZipEntry("0", payload),
            ),
        )
        AnkiPackageArchive.open(zip).use { archive ->
            assertTrue(archive.contains("meta"))
            assertTrue(archive.read("0").contentEquals(payload))
        }
    }
}
