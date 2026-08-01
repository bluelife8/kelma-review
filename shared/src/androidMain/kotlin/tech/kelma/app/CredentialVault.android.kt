package tech.kelma.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val VaultAlias = "tech.kelma.app.sync-token"
private const val VaultPreferences = "kelma-secure-credentials"

@Composable
actual fun rememberCredentialVault(): CredentialVault {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidCredentialVault(context) }
}

private class AndroidCredentialVault(context: Context) : CredentialVault {
    private val preferences = context.getSharedPreferences(VaultPreferences, Context.MODE_PRIVATE)

    override fun read(clientId: String): String? {
        val encoded = preferences.getString(clientId, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 12) { "Stored authentication token is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, 12))
        return cipher.doFinal(payload, 12, payload.size - 12).decodeToString()
    }

    override fun write(clientId: String, token: String) {
        require(token.isNotBlank()) { "Authentication token cannot be blank" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.encodeToByteArray())
        val payload = cipher.iv + encrypted
        check(preferences.edit().putString(clientId, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Could not persist the authentication token"
        }
    }

    override fun delete(clientId: String) {
        check(preferences.edit().remove(clientId).commit()) { "Could not remove the authentication token" }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(VaultAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                VaultAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
