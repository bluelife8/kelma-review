package tech.kelma.app

import kotlinx.serialization.Serializable

/** JSON-compatible values exchanged across the plugin boundary. */
@Serializable
sealed interface PluginValue {
    @Serializable data object Null : PluginValue
    @Serializable data class BooleanValue(val value: Boolean) : PluginValue
    @Serializable data class NumberValue(val value: Double) : PluginValue
    @Serializable data class StringValue(val value: String) : PluginValue
    @Serializable data class ListValue(val value: List<PluginValue>) : PluginValue
    @Serializable data class ObjectValue(val value: Map<String, PluginValue>) : PluginValue
}

/** Content-free application location supplied to a command invocation. */
data class PluginCommandContext(
    val screen: String = "unknown",
    val deckName: String? = null,
)

/** Invocation passed to a registered plugin command. */
data class PluginCommandInvocation(
    val commandId: String,
    val arguments: Map<String, PluginValue> = emptyMap(),
    val context: PluginCommandContext = PluginCommandContext(),
)

/** Versioned command registration exposed by the portable host. */
data class PluginCommand(
    val pluginId: String,
    val id: String,
    val title: String,
    val execute: suspend (PluginCommandInvocation) -> PluginValue,
)

/** Deterministic registry shared by built-in and Lua commands. */
class PluginCommandRegistry {
    private val commands = linkedMapOf<String, PluginCommand>()

    fun register(command: PluginCommand) {
        require(command.id.startsWith("${command.pluginId}.")) { "Command id must be namespaced by plugin id" }
        require(command.id !in commands) { "Command ${command.id} is already registered" }
        commands[command.id] = command
    }

    fun unregisterPlugin(pluginId: String) {
        commands.keys.filter { commands[it]?.pluginId == pluginId }.forEach(commands::remove)
    }

    fun list(): List<PluginCommand> = commands.values.toList()

    suspend fun invoke(invocation: PluginCommandInvocation): PluginValue =
        requireNotNull(commands[invocation.commandId]) { "Unknown command ${invocation.commandId}" }.execute(invocation)
}

/** Content-free lifecycle event delivered to interested plugins. */
data class PluginEvent(val name: String, val attributes: Map<String, PluginValue> = emptyMap())

/** Ordered event subscriptions; one plugin failure is attributed without mutating other registrations. */
class PluginEventRegistry {
    private data class Subscription(
        val pluginId: String,
        val eventName: String,
        val handler: suspend (PluginEvent) -> Unit,
    )

    private val subscriptions = mutableListOf<Subscription>()

    fun subscribe(pluginId: String, eventName: String, handler: suspend (PluginEvent) -> Unit) {
        subscriptions += Subscription(pluginId, eventName, handler)
    }

    fun unregisterPlugin(pluginId: String) {
        subscriptions.removeAll { it.pluginId == pluginId }
    }

    suspend fun publish(event: PluginEvent): List<PluginEventFailure> = buildList {
        subscriptions.filter { it.eventName == event.name }.forEach { subscription ->
            try {
                subscription.handler(event)
            } catch (exception: Exception) {
                add(PluginEventFailure(subscription.pluginId, event.name, exception.message ?: "Plugin event failed"))
            }
        }
    }
}

/** Content-free plugin failure suitable for diagnostics. */
data class PluginEventFailure(val pluginId: String, val eventName: String, val message: String)

/** Stable request supplied to a renderer extension. */
data class PluginRenderRequest(val rendererId: String, val html: String, val css: String)

/** Stable renderer result; platform WebViews remain owned by the host application. */
data class PluginRenderResult(val html: String, val css: String)

/** Discoverable renderer registration exposed to host assignment controls. */
data class PluginRendererRegistration(val pluginId: String, val rendererId: String)

/** Registry for plugin-provided pure render transforms. */
class PluginRendererRegistry {
    private val renderers = linkedMapOf<String, Pair<String, (PluginRenderRequest) -> PluginRenderResult>>()

    fun register(pluginId: String, rendererId: String, renderer: (PluginRenderRequest) -> PluginRenderResult) {
        require(rendererId.startsWith("$pluginId.")) { "Renderer id must be namespaced by plugin id" }
        require(rendererId !in renderers) { "Renderer is already registered" }
        renderers[rendererId] = pluginId to renderer
    }

    fun unregisterPlugin(pluginId: String) {
        renderers.keys.filter { renderers[it]?.first == pluginId }.forEach(renderers::remove)
    }

    fun list(): List<PluginRendererRegistration> = renderers.map { (rendererId, registration) ->
        PluginRendererRegistration(registration.first, rendererId)
    }

    fun contains(rendererId: String): Boolean = rendererId in renderers

    fun render(request: PluginRenderRequest): PluginRenderResult {
        val registered = requireNotNull(renderers[request.rendererId]) {
            "Unknown renderer ${request.rendererId}"
        }
        return try {
            registered.second(request)
        } catch (failure: Exception) {
            throw PluginRendererFailure(registered.first, request.rendererId, failure)
        }
    }
}

internal class PluginRendererFailure(
    val pluginId: String,
    val rendererId: String,
    cause: Exception,
) : IllegalStateException(
    "Plugin $pluginId renderer $rendererId failed: ${cause.message ?: "unknown error"}",
    cause,
)
