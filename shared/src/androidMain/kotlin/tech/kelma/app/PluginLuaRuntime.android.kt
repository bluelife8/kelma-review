package tech.kelma.app

internal actual fun createPlatformLuaRuntime(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
): PlatformLuaRuntime = LuaNativeBridge(pluginId, capabilities, files, entrypoint, limits)

private object AndroidLuaLibrary {
    init {
        System.loadLibrary("kelma_lua")
    }

    fun ensureLoaded() = Unit
}

internal class LuaNativeBridge(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
) : PlatformLuaRuntime {
    private var handle: Long

    init {
        AndroidLuaLibrary.ensureLoaded()
        handle = nativeCreate(pluginId, pluginCapabilityMask(capabilities), limits.memoryBytes, limits.instructionCount)
        try {
            files.filterKeys { it.endsWith(".lua") }.toSortedMap().forEach { (path, source) ->
                nativeAddFile(handle, path, source)
            }
            nativeStart(handle, KelmaLuaBootstrap.encodeToByteArray(), entrypoint)
        } catch (failure: Throwable) {
            nativeClose(handle)
            handle = 0
            throw failure
        }
    }

    override val commands: List<PluginRuntimeCommand>
        get() = List(nativeCount(activeHandle(), CommandsKind)) { index ->
            PluginRuntimeCommand(
                nativeMetadata(activeHandle(), CommandsKind, index, 0).decodeBoundaryString(),
                nativeMetadata(activeHandle(), CommandsKind, index, 1).decodeBoundaryString(),
            )
        }
    override val eventNames: Set<String>
        get() = List(nativeCount(activeHandle(), EventsKind)) { index ->
            nativeMetadata(activeHandle(), EventsKind, index, 0).decodeBoundaryString()
        }.toSet()
    override val rendererIds: Set<String>
        get() = List(nativeCount(activeHandle(), RenderersKind)) { index ->
            nativeMetadata(activeHandle(), RenderersKind, index, 0).decodeBoundaryString()
        }.toSet()

    override fun invoke(commandId: String, argumentsJson: String): String =
        nativeInvoke(activeHandle(), commandId, argumentsJson.encodeToByteArray()).decodeBoundaryString()

    override fun publish(eventName: String, attributesJson: String) {
        nativePublish(activeHandle(), eventName, attributesJson.encodeToByteArray())
    }

    override fun render(rendererId: String, html: String, css: String): PluginRenderResult {
        val rendered = nativeRender(
            activeHandle(),
            rendererId,
            html.encodeToByteArray(),
            css.encodeToByteArray(),
        )
        return PluginRenderResult(rendered[0].decodeBoundaryString(), rendered[1].decodeBoundaryString())
    }

    override fun drainLogs(): List<PluginRuntimeLog> {
        val logs = List(nativeCount(activeHandle(), LogsKind)) { index ->
            PluginRuntimeLog(
                nativeMetadata(activeHandle(), LogsKind, index, 0).decodeToString(),
                nativeMetadata(activeHandle(), LogsKind, index, 1).decodeToString(),
            )
        }
        nativeClearLogs(activeHandle())
        return logs
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    private fun activeHandle(): Long = handle.takeIf { it != 0L } ?: error("Lua runtime is closed")

    private external fun nativeCreate(pluginId: String, capabilities: Int, memoryLimit: Long, instructionLimit: Long): Long
    private external fun nativeAddFile(handle: Long, path: String, source: ByteArray)
    private external fun nativeStart(handle: Long, bootstrap: ByteArray, entrypoint: String)
    private external fun nativeCount(handle: Long, kind: Int): Int
    private external fun nativeMetadata(handle: Long, kind: Int, index: Int, field: Int): ByteArray
    private external fun nativeInvoke(handle: Long, commandId: String, argumentsJson: ByteArray): ByteArray
    private external fun nativePublish(handle: Long, eventName: String, attributesJson: ByteArray)
    private external fun nativeRender(
        handle: Long,
        rendererId: String,
        html: ByteArray,
        css: ByteArray,
    ): Array<ByteArray>
    private external fun nativeClearLogs(handle: Long)
    private external fun nativeClose(handle: Long)
}

private fun ByteArray.decodeBoundaryString(): String = decodeToString(throwOnInvalidSequence = true)

private const val CommandsKind = 0
private const val EventsKind = 1
private const val RenderersKind = 2
private const val LogsKind = 3
