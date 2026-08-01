package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

@Composable
internal actual fun rememberLocalAccountRegistryStorage(): LocalAccountRegistryStorage = remember {
    val defaults = NSUserDefaults.standardUserDefaults
    object : LocalAccountRegistryStorage {
        override fun read(): String? = defaults.stringForKey("kelma.localAccounts.registry")

        override fun write(value: String) {
            defaults.setObject(value, forKey = "kelma.localAccounts.registry")
        }
    }
}
