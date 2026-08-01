package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val IosVaultService = "tech.kelma.app"

@Composable
actual fun rememberCredentialVault(): CredentialVault = remember { IosCredentialVault() }

@OptIn(ExperimentalForeignApi::class)
internal class IosCredentialVault : CredentialVault {
    override fun read(clientId: String): String? = withQuery(clientId, includeReturnData = true) { query ->
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@withQuery null
            check(status == errSecSuccess) { "The iOS Keychain rejected the credential request (status $status)" }
            val value = requireNotNull(result.value) { "The iOS Keychain returned no credential data" }
            try {
                val data: CFDataRef = value.reinterpret()
                val length = CFDataGetLength(data)
                CFDataGetBytePtr(data)?.readBytes(length.toInt())?.decodeToString()
            } finally {
                CFRelease(value)
            }
        }
    }

    override fun write(clientId: String, token: String) {
        require(token.isNotBlank()) { "Authentication token cannot be blank" }
        val bytes = token.encodeToByteArray()
        bytes.usePinned { pinned ->
            val data = CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong())
            requireNotNull(data) { "Authentication token could not be encoded" }
            try {
                withDictionary { attributes ->
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    val updated = withQuery(clientId) { query -> SecItemUpdate(query, attributes) }
                    if (updated == errSecItemNotFound) {
                        withQuery(clientId) { query ->
                            CFDictionarySetValue(query, kSecValueData, data)
                            CFDictionarySetValue(
                                query,
                                kSecAttrAccessible,
                                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                            )
                            check(SecItemAdd(query, null) == errSecSuccess) {
                                "Could not persist the authentication token in iOS Keychain"
                            }
                        }
                    } else {
                        check(updated == errSecSuccess) {
                            "Could not update the authentication token in iOS Keychain (status $updated)"
                        }
                    }
                }
            } finally {
                CFRelease(data)
            }
        }
    }

    override fun delete(clientId: String) {
        val status = withQuery(clientId) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Could not remove the authentication token from iOS Keychain (status $status)"
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withQuery(
    clientId: String,
    includeReturnData: Boolean = false,
    block: (CFDictionaryRef) -> T,
): T {
    val service = CFStringCreateWithCString(null, IosVaultService, kCFStringEncodingUTF8)
    val account = CFStringCreateWithCString(null, clientId, kCFStringEncodingUTF8)
    requireNotNull(service)
    requireNotNull(account)
    try {
        return withDictionary { query ->
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            if (includeReturnData) {
                CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
                CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            }
            block(query)
        }
    } finally {
        CFRelease(service)
        CFRelease(account)
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withDictionary(block: (platform.CoreFoundation.CFMutableDictionaryRef) -> T): T {
    val dictionary = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
    try {
        return block(dictionary)
    } finally {
        CFRelease(dictionary)
    }
}
