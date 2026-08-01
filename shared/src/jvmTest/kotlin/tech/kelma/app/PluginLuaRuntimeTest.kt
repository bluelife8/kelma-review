package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PluginLuaRuntimeTest {
    @Test
    fun nativeLua54LoadsModulesAndBridgesCommandsEventsAndRenderers() {
        val runtime = createPlatformLuaRuntime(
            pluginId = "tech.kelma.runtime",
            capabilities = setOf(PluginCapability.Commands, PluginCapability.Events, PluginCapability.Ui),
            files = mapOf(
                "lua/sample/init.lua" to "return { suffix = ' ✓🚀' }".encodeToByteArray(),
                "plugin/init.lua" to
                    """
                    assert(_VERSION == 'Lua 5.4')
                    assert(io == nil and os == nil and debug == nil and load == nil and loadfile == nil and dofile == nil)
                    assert(string.dump == nil)
                    local sample = require('sample')
                    kelma.commands.register('tech.kelma.runtime.greet', 'Greet 🚀', function(arguments)
                      if arguments.fail then error('boom 🚀') end
                      return { message = arguments.name .. sample.suffix, missing = kelma.json.null }
                    end)
                    kelma.events.subscribe('review.completed', function(event)
                      kelma.log.info(event.name .. ':' .. event.attributes.rating .. ' 🧠')
                    end)
                    kelma.ui.register_renderer('tech.kelma.runtime.wrap', function(request)
                      return { html = '<b>' .. request.html .. '</b>', css = request.css .. '.plugin{}' }
                    end)
                    kelma.log.info('started')
                    """.trimIndent().encodeToByteArray(),
            ),
            entrypoint = "plugin/init.lua",
        )
        try {
            assertEquals(listOf("tech.kelma.runtime.greet"), runtime.commands.map(PluginRuntimeCommand::id))
            assertEquals("Greet 🚀", runtime.commands.single().title)
            assertEquals(setOf("review.completed"), runtime.eventNames)
            assertEquals(setOf("tech.kelma.runtime.wrap"), runtime.rendererIds)
            assertEquals(listOf("started"), runtime.drainLogs().map(PluginRuntimeLog::message))

            val result = Json.parseToJsonElement(
                runtime.invoke("tech.kelma.runtime.greet", "{\"name\":\"Kelma 🧠\"}"),
            ).jsonObject
            assertEquals("Kelma 🧠 ✓🚀", result.getValue("message").jsonPrimitive.content)
            assertNull(result.getValue("missing").jsonPrimitive.contentOrNull)
            val unicodeFailure = assertFailsWith<IllegalStateException> {
                runtime.invoke("tech.kelma.runtime.greet", "{\"fail\":true}")
            }
            assertTrue(unicodeFailure.message.orEmpty().contains("boom 🚀"))

            runtime.publish("review.completed", "{\"rating\":\"Good 🚀\"}")
            assertEquals("review.completed:Good 🚀 🧠", runtime.drainLogs().single().message)
            assertEquals(
                PluginRenderResult("<b>card 🧠</b>", "body{}.plugin{}"),
                runtime.render("tech.kelma.runtime.wrap", "card 🧠", "body{}"),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun capabilityAndInstructionLimitsStopPlugins() {
        val denied = assertFailsWith<IllegalStateException> {
            createPlatformLuaRuntime(
                pluginId = "tech.kelma.denied",
                capabilities = emptySet(),
                files = mapOf(
                    "plugin/init.lua" to
                        "kelma.commands.register('tech.kelma.denied.nope', 'Nope', function() end)".encodeToByteArray(),
                ),
                entrypoint = "plugin/init.lua",
            )
        }
        assertTrue(denied.message.orEmpty().contains("capability denied", ignoreCase = true))

        val limited = assertFailsWith<IllegalStateException> {
            createPlatformLuaRuntime(
                pluginId = "tech.kelma.loop",
                capabilities = emptySet(),
                files = mapOf("plugin/init.lua" to "while true do end".encodeToByteArray()),
                entrypoint = "plugin/init.lua",
                limits = PluginRuntimeLimits(instructionCount = 10_000),
            )
        }
        assertTrue(limited.message.orEmpty().contains("instruction limit"))

        val memoryLimited = assertFailsWith<IllegalStateException> {
            createPlatformLuaRuntime(
                pluginId = "tech.kelma.memory",
                capabilities = emptySet(),
                files = mapOf(
                    "plugin/init.lua" to
                        "local values = {}; while true do values[#values + 1] = string.rep('x', 4096) end"
                            .encodeToByteArray(),
                ),
                entrypoint = "plugin/init.lua",
                limits = PluginRuntimeLimits(memoryBytes = 1024L * 1024, instructionCount = 5_000_000),
            )
        }
        assertTrue(memoryLimited.message.orEmpty().contains("memory", ignoreCase = true))
    }
}
