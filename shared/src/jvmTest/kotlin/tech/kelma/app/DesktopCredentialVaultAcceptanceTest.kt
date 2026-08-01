package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopCredentialVaultAcceptanceTest {
    @Test
    fun operatingSystemVaultRoundTrip() {
        if (System.getenv("KELMA_REQUIRE_DESKTOP_VAULT_ACCEPTANCE") != "true") return
        val clientId = "desktop-test-${randomUuidString()}"
        val vault = DesktopCredentialVault()
        try {
            assertNull(vault.read(clientId))
            vault.write(clientId, "desktop-vault-test-token")
            assertEquals("desktop-vault-test-token", vault.read(clientId))
        } finally {
            vault.delete(clientId)
        }
        assertNull(vault.read(clientId))
    }
}
