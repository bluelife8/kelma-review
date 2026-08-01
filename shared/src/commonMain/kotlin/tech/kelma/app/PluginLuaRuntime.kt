package tech.kelma.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class PluginRuntimeLimits(
    val memoryBytes: Long = 32L * 1024 * 1024,
    val instructionCount: Long = 5_000_000,
)

internal data class PluginRuntimeCommand(val id: String, val title: String)
internal data class PluginRuntimeLog(val level: String, val message: String)

internal interface PlatformLuaRuntime : AutoCloseable {
    val commands: List<PluginRuntimeCommand>
    val eventNames: Set<String>
    val rendererIds: Set<String>

    fun invoke(commandId: String, argumentsJson: String): String
    fun publish(eventName: String, attributesJson: String)
    fun render(rendererId: String, html: String, css: String): PluginRenderResult
    fun drainLogs(): List<PluginRuntimeLog>
}

internal expect fun createPlatformLuaRuntime(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits = PluginRuntimeLimits(),
): PlatformLuaRuntime

internal fun PluginValue.toBoundaryJson(): JsonElement = when (this) {
    PluginValue.Null -> JsonNull
    is PluginValue.BooleanValue -> JsonPrimitive(value)
    is PluginValue.NumberValue -> {
        require(value.isFinite()) { "Plugin numbers must be finite" }
        JsonPrimitive(value)
    }
    is PluginValue.StringValue -> JsonPrimitive(value)
    is PluginValue.ListValue -> JsonArray(value.map(PluginValue::toBoundaryJson))
    is PluginValue.ObjectValue -> JsonObject(value.mapValues { it.value.toBoundaryJson() })
}

internal fun JsonElement.toPluginValue(): PluginValue = when (this) {
    JsonNull -> PluginValue.Null
    is JsonArray -> PluginValue.ListValue(map(JsonElement::toPluginValue))
    is JsonObject -> PluginValue.ObjectValue(mapValues { it.value.toPluginValue() })
    is JsonPrimitive -> when {
        isString -> PluginValue.StringValue(content)
        booleanOrNull != null -> PluginValue.BooleanValue(booleanOrNull == true)
        doubleOrNull != null && doubleOrNull?.isFinite() == true ->
            PluginValue.NumberValue(requireNotNull(doubleOrNull))
        else -> error("Plugin returned an invalid JSON value")
    }
}

internal fun pluginCapabilityMask(capabilities: Set<PluginCapability>): Int =
    (if (PluginCapability.Commands in capabilities) 1 else 0) or
        (if (PluginCapability.Events in capabilities) 2 else 0) or
        (if (PluginCapability.Ui in capabilities) 4 else 0)
