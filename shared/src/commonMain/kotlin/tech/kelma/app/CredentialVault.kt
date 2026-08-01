package tech.kelma.app

import androidx.compose.runtime.Composable

/** Stores authentication tokens outside the collection database. */
interface CredentialVault {
    fun read(clientId: String): String?
    fun write(clientId: String, token: String)
    fun delete(clientId: String)
}

class InMemoryCredentialVault : CredentialVault {
    private val tokens = mutableMapOf<String, String>()

    override fun read(clientId: String): String? = tokens[clientId]

    override fun write(clientId: String, token: String) {
        require(token.isNotBlank()) { "Authentication token cannot be blank" }
        tokens[clientId] = token
    }

    override fun delete(clientId: String) {
        tokens.remove(clientId)
    }
}

@Composable
expect fun rememberCredentialVault(): CredentialVault
