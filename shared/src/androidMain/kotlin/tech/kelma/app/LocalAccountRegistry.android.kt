package tech.kelma.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberLocalAccountRegistryStorage(): LocalAccountRegistryStorage {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val preferences = context.getSharedPreferences("kelma-local-accounts", Context.MODE_PRIVATE)
        object : LocalAccountRegistryStorage {
            override fun read(): String? = preferences.getString("registry", null)

            override fun write(value: String) {
                check(preferences.edit().putString("registry", value).commit()) {
                    "Could not save the local account registry"
                }
            }
        }
    }
}
