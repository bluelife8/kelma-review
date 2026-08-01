package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginLuaIOSTest {
    @Test
    fun standardLua54ExecutesThroughNativeCInterop() {
        val runtime = createPlatformLuaRuntime(
            pluginId = "tech.kelma.ios",
            capabilities = setOf(PluginCapability.Commands),
            files = mapOf(
                "lua/math_helper.lua" to "return { double = function(value) return value * 2 end }".encodeToByteArray(),
                "plugin/init.lua" to
                    """
                    local helper = require('math_helper')
                    kelma.commands.register('tech.kelma.ios.double', 'Double 🚀', function(arguments)
                      return { value = helper.double(arguments.value), label = arguments.label }
                    end)
                    """.trimIndent().encodeToByteArray(),
            ),
            entrypoint = "plugin/init.lua",
        )
        try {
            assertEquals("Double 🚀", runtime.commands.single().title)
            assertEquals(
                "{\"label\":\"🧠\",\"value\":8}",
                runtime.invoke("tech.kelma.ios.double", "{\"value\":4,\"label\":\"🧠\"}"),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun instructionBudgetInterruptsRunawayIosPlugin() {
        val failure = assertFailsWith<IllegalStateException> {
            createPlatformLuaRuntime(
                pluginId = "tech.kelma.iosloop",
                capabilities = emptySet(),
                files = mapOf("plugin/init.lua" to "while true do end".encodeToByteArray()),
                entrypoint = "plugin/init.lua",
                limits = PluginRuntimeLimits(instructionCount = 10_000),
            )
        }
        assertTrue(failure.message.orEmpty().contains("instruction limit"))
    }
}
