package tech.kelma.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AccountDeviceActions(
    val username: String?,
    val signOut: () -> Unit,
    val removeFromDevice: () -> Unit,
)

internal fun accountDeviceActions(
    accountRegistry: LocalAccountRegistry,
    store: PersistentCollectionStore,
    luaPluginHost: LuaPluginHost,
    scope: CoroutineScope,
    isWorking: () -> Boolean,
    isRestored: () -> Boolean,
    setWorking: (Boolean) -> Unit,
    setError: (String) -> Unit,
    setPluginHostState: (PluginHostState) -> Unit,
    leaveAccount: () -> Unit,
): AccountDeviceActions {
    val activeAccount = accountRegistry.activeAccount()
    val signOut: () -> Unit = signOut@{
        if (isWorking() || !isRestored()) return@signOut
        setWorking(true)
        scope.launch {
            try {
                withContext(Dispatchers.Default) { store.signOutPreservingCollection() }
                setWorking(false)
                leaveAccount()
            } catch (exception: Exception) {
                setError(exception.message ?: "Could not sign out on this device")
                setWorking(false)
            }
        }
    }
    val removeFromDevice: () -> Unit = remove@{
        val account = activeAccount
        if (isWorking() || !isRestored()) return@remove
        if (account == null) {
            setError("The active local account could not be identified")
            return@remove
        }
        setWorking(true)
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    luaPluginHost.close()
                    store.clearAll()
                }
                accountRegistry.remove(account.endpoint, account.username)
                setPluginHostState(luaPluginHost.state())
                setWorking(false)
                leaveAccount()
            } catch (exception: Exception) {
                setError(exception.message ?: "Could not remove this account from the device")
                setWorking(false)
            }
        }
    }
    return AccountDeviceActions(activeAccount?.username, signOut, removeFromDevice)
}
