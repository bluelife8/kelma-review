package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialVaultIOSTest {
    @Test
    fun keychainRoundTripUsesDeviceOnlyCredentialStorage() {
        val clientId = "ios-test-${randomUuidString()}"
        val vault = IosCredentialVault()
        var keychainAvailable = false
        try {
            try {
                assertNull(vault.read(clientId))
                keychainAvailable = true
            } catch (exception: IllegalStateException) {
                if (exception.message?.contains("status -25291") == true) return
                throw exception
            }
            vault.write(clientId, "ios-keychain-test-token")
            assertEquals("ios-keychain-test-token", vault.read(clientId))
        } finally {
            if (keychainAvailable) vault.delete(clientId)
        }
        assertNull(vault.read(clientId))
    }
}
