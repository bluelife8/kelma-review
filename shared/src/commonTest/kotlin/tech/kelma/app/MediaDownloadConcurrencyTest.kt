package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaDownloadConcurrencyTest {
    @Test
    fun ordinaryMediaDownloadsUseFixedKelmaSyncConcurrency() {
        assertEquals(64, MediaDownloadWorkers)
    }

    @Test
    fun massMediaPreparesUpToFiveTarsButDownloadsSequentially() {
        assertEquals(5, MaximumConcurrentMediaTarPreparations)
    }
}
