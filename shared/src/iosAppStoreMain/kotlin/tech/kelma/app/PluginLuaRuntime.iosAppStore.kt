package tech.kelma.app

@Suppress("UNUSED_PARAMETER")
internal actual fun createPlatformLuaRuntime(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
): PlatformLuaRuntime = error("External plugins are unavailable in the App Store build")
