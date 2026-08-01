package tech.kelma.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class RunningPlugin(
    val pluginId: String,
    val startupMillis: Long,
    val commands: List<PluginRuntimeCommand>,
    val eventNames: Set<String>,
    val rendererIds: Set<String>,
)

internal data class PluginHostState(
    val installed: List<InstalledPlugin> = emptyList(),
    val running: List<RunningPlugin> = emptyList(),
    val safeMode: Boolean = false,
    val runtimeGeneration: Long = 0,
) {
    val commandCount: Int get() = running.sumOf { it.commands.size }
    val eventCount: Int get() = running.sumOf { it.eventNames.size }
    val rendererCount: Int get() = running.sumOf { it.rendererIds.size }
}

internal class LuaPluginHost(
    private val persistence: PluginPersistence,
    private val json: Json,
    private val commands: PluginCommandRegistry,
    private val events: PluginEventRegistry,
    private val renderers: PluginRendererRegistry,
    private val clock: () -> Long = ::currentEpochMillis,
    private val runtimeFactory: (
        String,
        Set<PluginCapability>,
        Map<String, ByteArray>,
        String,
        PluginRuntimeLimits,
    ) -> PlatformLuaRuntime = ::createPlatformLuaRuntime,
) : AutoCloseable {
    private val runtimes = linkedMapOf<String, PlatformLuaRuntime>()
    private val lifecycleMutex = Mutex()
    private val runtimeMutex = Mutex()
    private var runtimeGeneration = 0L
    private var state = PluginHostState()

    fun state(): PluginHostState = state

    suspend fun reload(): PluginHostState = lifecycleMutex.withLock {
        runtimeMutex.withLock { reloadRuntimes() }
        recordEventFailures(events.publish(PluginEvent("app.started")))
        runtimeMutex.withLock { flushAllLogs() }
        state = state.copy(installed = persistence.list())
        state
    }

    private suspend fun reloadRuntimes(): PluginHostState {
        stopRuntimes()
        runtimeGeneration++
        val installed = persistence.list()
        val safeMode = persistence.safeMode()
        if (safeMode) {
            state = PluginHostState(installed, safeMode = true, runtimeGeneration = runtimeGeneration)
            return state
        }
        val resolution = resolvePluginLoadOrder(installed)
        resolution.blocked.forEach { (pluginId, reason) ->
            persistence.setStatus(pluginId, PluginStatus.Blocked, reason, clock())
        }
        val installedById = installed.associateBy { it.manifest.id }
        val running = mutableListOf<RunningPlugin>()
        resolution.loadOrder.forEach { plugin ->
            val blockedDependency = plugin.manifest.dependencies
                .filterNot(PluginDependency::optional)
                .firstOrNull { it.id !in runtimes }
            if (blockedDependency != null) {
                persistence.setStatus(
                    plugin.manifest.id,
                    PluginStatus.Blocked,
                    "Dependency ${blockedDependency.id} did not start",
                    clock(),
                )
                return@forEach
            }
            val startedAt = clock()
            val runtime = try {
                runtimeFactory(
                    plugin.manifest.id,
                    plugin.manifest.capabilities,
                    runtimeFiles(plugin, installedById),
                    plugin.manifest.entrypoint,
                    PluginRuntimeLimits(),
                )
            } catch (failure: Throwable) {
                persistence.setStatus(
                    plugin.manifest.id,
                    PluginStatus.Failed,
                    failure.message ?: "Lua plugin failed to start",
                    clock(),
                )
                return@forEach
            }
            try {
                register(plugin, runtime)
                runtimes[plugin.manifest.id] = runtime
                flushLogs(plugin.manifest.id, runtime)
                persistence.setStatus(plugin.manifest.id, PluginStatus.Installed, null, clock())
                running += RunningPlugin(
                    plugin.manifest.id,
                    (clock() - startedAt).coerceAtLeast(0),
                    runtime.commands,
                    runtime.eventNames,
                    runtime.rendererIds,
                )
            } catch (failure: Throwable) {
                unregister(plugin.manifest.id)
                runtime.close()
                persistence.setStatus(
                    plugin.manifest.id,
                    PluginStatus.Failed,
                    failure.message ?: "Lua plugin registration failed",
                    clock(),
                )
            }
        }
        state = PluginHostState(
            persistence.list(),
            running,
            safeMode = false,
            runtimeGeneration = runtimeGeneration,
        )
        return state
    }

    fun prepareInstall(document: InterchangeDocument): PluginPackage = decodePluginPackage(document, json)

    suspend fun install(document: InterchangeDocument): PluginHostState = install(prepareInstall(document))

    suspend fun install(pluginPackage: PluginPackage): PluginHostState {
        persistence.install(pluginPackage, clock())
        return reload()
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): PluginHostState {
        persistence.setEnabled(pluginId, enabled, clock())
        return reload()
    }

    suspend fun setSafeMode(enabled: Boolean): PluginHostState {
        persistence.setSafeMode(enabled)
        return reload()
    }

    suspend fun uninstall(pluginId: String): PluginHostState {
        persistence.uninstall(pluginId)
        return reload()
    }

    suspend fun publish(event: PluginEvent): List<PluginEventFailure> {
        val failures = events.publish(event)
        recordEventFailures(failures)
        runtimeMutex.withLock { flushAllLogs() }
        state = state.copy(installed = persistence.list())
        return failures
    }

    fun logs(pluginId: String): List<PluginLogEntry> = persistence.logs(pluginId)

    suspend fun render(request: PluginRenderRequest): PluginRenderResult = runtimeMutex.withLock {
        try {
            renderers.render(request)
        } catch (failure: PluginRendererFailure) {
            recordRuntimeFailure(failure.pluginId, "renderer ${failure.rendererId}", failure)
            throw failure
        }
    }

    private fun runtimeFiles(
        plugin: InstalledPlugin,
        installed: Map<String, InstalledPlugin>,
    ): Map<String, ByteArray> = buildMap {
        val visited = mutableSetOf<String>()
        fun addDependencies(candidate: InstalledPlugin) {
            candidate.manifest.dependencies.sortedBy(PluginDependency::id).forEach { dependency ->
                val target = installed[dependency.id]?.takeIf {
                    it.manifest.id in runtimes &&
                        SemanticVersion.parse(it.manifest.version) >= SemanticVersion.parse(dependency.minimumVersion)
                } ?: return@forEach
                if (visited.add(target.manifest.id)) {
                    addDependencies(target)
                    persistence.files(target.manifest.id)
                        .filterKeys { it.startsWith("lua/") && it.endsWith(".lua") }
                        .forEach { (path, content) -> put(path, content) }
                }
            }
        }
        addDependencies(plugin)
        persistence.files(plugin.manifest.id).forEach { (path, content) -> put(path, content) }
    }

    private fun register(plugin: InstalledPlugin, runtime: PlatformLuaRuntime) {
        runtime.commands.forEach { command ->
            commands.register(
                PluginCommand(plugin.manifest.id, command.id, command.title) { invocation ->
                    try {
                        val values = invocation.arguments.mapValues { it.value.toBoundaryJson() }.toMutableMap()
                        values["kelma_context"] = PluginValue.ObjectValue(
                            buildMap {
                                put("screen", PluginValue.StringValue(invocation.context.screen))
                                invocation.context.deckName?.let {
                                    put("deck_name", PluginValue.StringValue(it))
                                }
                            },
                        ).toBoundaryJson()
                        val arguments = JsonObject(values)
                        val result = runtimeMutex.withLock {
                            runtime.invoke(
                                command.id,
                                json.encodeToString(JsonElement.serializer(), arguments),
                            )
                        }
                        json.parseToJsonElement(result).toPluginValue()
                    } catch (failure: Exception) {
                        recordRuntimeFailure(plugin.manifest.id, "command ${command.id}", failure)
                        throw failure
                    } finally {
                        runtimeMutex.withLock { flushLogs(plugin.manifest.id, runtime) }
                    }
                },
            )
        }
        runtime.eventNames.forEach { eventName ->
            events.subscribe(plugin.manifest.id, eventName) { event ->
                val attributes = JsonObject(event.attributes.mapValues { it.value.toBoundaryJson() })
                runtimeMutex.withLock {
                    runtime.publish(event.name, json.encodeToString(JsonElement.serializer(), attributes))
                    flushLogs(plugin.manifest.id, runtime)
                }
            }
        }
        runtime.rendererIds.forEach { rendererId ->
            renderers.register(plugin.manifest.id, rendererId) { request ->
                try {
                    runtime.render(rendererId, request.html, request.css).validated()
                } finally {
                    flushLogs(plugin.manifest.id, runtime)
                }
            }
        }
    }

    private fun recordRuntimeFailure(pluginId: String, operation: String, failure: Exception) {
        val message = "$operation: ${failure.message ?: "plugin runtime failed"}"
        persistence.setStatus(pluginId, PluginStatus.Failed, message, clock())
        persistence.appendLogs(pluginId, listOf(PluginRuntimeLog("error", message)), clock())
        state = state.copy(installed = persistence.list())
    }

    private fun recordEventFailures(failures: List<PluginEventFailure>) {
        failures.forEach { failure ->
            persistence.setStatus(failure.pluginId, PluginStatus.Failed, failure.message, clock())
            persistence.appendLogs(
                failure.pluginId,
                listOf(PluginRuntimeLog("error", "${failure.eventName}: ${failure.message}")),
                clock(),
            )
        }
    }

    private fun flushAllLogs() {
        runtimes.forEach { (pluginId, runtime) -> flushLogs(pluginId, runtime) }
    }

    private fun flushLogs(pluginId: String, runtime: PlatformLuaRuntime) {
        persistence.appendLogs(pluginId, runtime.drainLogs(), clock())
    }

    private fun unregister(pluginId: String) {
        commands.unregisterPlugin(pluginId)
        events.unregisterPlugin(pluginId)
        renderers.unregisterPlugin(pluginId)
    }

    private fun stopRuntimes() {
        runtimes.forEach { (pluginId, runtime) ->
            unregister(pluginId)
            runtime.close()
        }
        runtimes.clear()
    }

    override fun close() {
        stopRuntimes()
        state = state.copy(running = emptyList())
    }
}
