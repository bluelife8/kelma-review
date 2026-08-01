@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package tech.kelma.app

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import cnames.structs.kelma_lua_runtime
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import tech.kelma.lua.kelma_lua_add_file
import tech.kelma.lua.kelma_lua_clear_logs
import tech.kelma.lua.kelma_lua_close
import tech.kelma.lua.kelma_lua_command_count
import tech.kelma.lua.kelma_lua_command_id
import tech.kelma.lua.kelma_lua_command_title
import tech.kelma.lua.kelma_lua_event_count
import tech.kelma.lua.kelma_lua_event_name
import tech.kelma.lua.kelma_lua_free_string
import tech.kelma.lua.kelma_lua_invoke_command
import tech.kelma.lua.kelma_lua_last_error
import tech.kelma.lua.kelma_lua_log_count
import tech.kelma.lua.kelma_lua_log_level
import tech.kelma.lua.kelma_lua_log_message
import tech.kelma.lua.kelma_lua_new
import tech.kelma.lua.kelma_lua_publish_event
import tech.kelma.lua.kelma_lua_render
import tech.kelma.lua.kelma_lua_renderer_count
import tech.kelma.lua.kelma_lua_renderer_id
import tech.kelma.lua.kelma_lua_start

internal actual fun createPlatformLuaRuntime(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
): PlatformLuaRuntime = IosLuaRuntime(pluginId, capabilities, files, entrypoint, limits)

private class IosLuaRuntime(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
) : PlatformLuaRuntime {
    private var runtime: CPointer<kelma_lua_runtime>?

    init {
        runtime = kelma_lua_new(
            pluginId,
            pluginCapabilityMask(capabilities).toUInt(),
            limits.memoryBytes.convert(),
            limits.instructionCount,
        ) ?: error("Could not create Lua 5.4 runtime")
        try {
            files.filterKeys { it.endsWith(".lua") }.entries.sortedBy { it.key }.forEach { (path, source) ->
                val text = source.decodeToString(throwOnInvalidSequence = true)
                checkStatus(
                    kelma_lua_add_file(activeRuntime(), path, text, source.size.convert()),
                    "Could not add Lua plugin file",
                )
            }
            checkStatus(
                kelma_lua_start(
                    activeRuntime(),
                    KelmaLuaBootstrap,
                    KelmaLuaBootstrap.encodeToByteArray().size.convert(),
                    entrypoint,
                ),
                "Could not start Lua plugin",
            )
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override val commands: List<PluginRuntimeCommand>
        get() = List(kelma_lua_command_count(activeRuntime())) { index ->
            PluginRuntimeCommand(
                requireNotNull(kelma_lua_command_id(activeRuntime(), index)).toKString(),
                requireNotNull(kelma_lua_command_title(activeRuntime(), index)).toKString(),
            )
        }
    override val eventNames: Set<String>
        get() = List(kelma_lua_event_count(activeRuntime())) { index ->
            requireNotNull(kelma_lua_event_name(activeRuntime(), index)).toKString()
        }.toSet()
    override val rendererIds: Set<String>
        get() = List(kelma_lua_renderer_count(activeRuntime())) { index ->
            requireNotNull(kelma_lua_renderer_id(activeRuntime(), index)).toKString()
        }.toSet()

    override fun invoke(commandId: String, argumentsJson: String): String = memScoped {
        val result = alloc<CPointerVar<ByteVar>>()
        checkStatus(
            kelma_lua_invoke_command(activeRuntime(), commandId, argumentsJson, result.ptr),
            "Lua command failed",
        )
        consumeString(result.value)
    }

    override fun publish(eventName: String, attributesJson: String) {
        checkStatus(
            kelma_lua_publish_event(activeRuntime(), eventName, attributesJson),
            "Lua event failed",
        )
    }

    override fun render(rendererId: String, html: String, css: String): PluginRenderResult = memScoped {
        val resultHtml = alloc<CPointerVar<ByteVar>>()
        val resultCss = alloc<CPointerVar<ByteVar>>()
        checkStatus(
            kelma_lua_render(activeRuntime(), rendererId, html, css, resultHtml.ptr, resultCss.ptr),
            "Lua renderer failed",
        )
        PluginRenderResult(consumeString(resultHtml.value), consumeString(resultCss.value))
    }

    override fun drainLogs(): List<PluginRuntimeLog> {
        val logs = List(kelma_lua_log_count(activeRuntime())) { index ->
            PluginRuntimeLog(
                requireNotNull(kelma_lua_log_level(activeRuntime(), index)).toKString(),
                requireNotNull(kelma_lua_log_message(activeRuntime(), index)).toKString(),
            )
        }
        kelma_lua_clear_logs(activeRuntime())
        return logs
    }

    private fun consumeString(value: CPointer<ByteVar>?): String {
        val pointer = requireNotNull(value) { "Lua runtime returned no value" }
        return pointer.toKString().also { kelma_lua_free_string(pointer) }
    }

    private fun checkStatus(status: Int, fallback: String) {
        if (status != 0) return
        val message = runtime?.let { kelma_lua_last_error(it)?.toKString() } ?: fallback
        error(message)
    }

    private fun activeRuntime(): CPointer<kelma_lua_runtime> = runtime ?: error("Lua runtime is closed")

    override fun close() {
        runtime?.let { kelma_lua_close(it) }
        runtime = null
    }
}
