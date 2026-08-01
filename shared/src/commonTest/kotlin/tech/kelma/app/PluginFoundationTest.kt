package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PluginFoundationTest {
    @Test
    fun manifestsValidateAndDependenciesResolveDeterministically() {
        val base = installed(manifest("tech.kelma.base", "1.2.0"))
        val feature = installed(
            manifest(
                "tech.kelma.feature",
                "2.0.0",
                listOf(PluginDependency(base.manifest.id, "1.1.0")),
            ),
        )

        val resolution = resolvePluginLoadOrder(listOf(feature, base))

        assertEquals(listOf(base.manifest.id, feature.manifest.id), resolution.loadOrder.map { it.manifest.id })
        assertTrue(resolution.blocked.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            manifest("invalid", "1.0.0").validated()
        }
        assertFailsWith<IllegalArgumentException> {
            manifest("tech.kelma.path", "1.0.0").copy(entrypoint = "plugin/é.lua").validated()
        }
    }

    @Test
    fun missingOldAndCyclicDependenciesAreBlocked() {
        val base = installed(manifest("tech.kelma.base", "1.0.0"))
        val old = installed(manifest("tech.kelma.old", "1.0.0", listOf(PluginDependency(base.manifest.id, "2.0.0"))))
        val first = installed(manifest("tech.kelma.first", "1.0.0", listOf(PluginDependency("tech.kelma.second", "1.0.0"))))
        val second = installed(manifest("tech.kelma.second", "1.0.0", listOf(PluginDependency("tech.kelma.first", "1.0.0"))))

        val resolution = resolvePluginLoadOrder(listOf(base, old, first, second))

        assertEquals(listOf(base.manifest.id), resolution.loadOrder.map { it.manifest.id })
        assertTrue(old.manifest.id in resolution.blocked)
        assertTrue(first.manifest.id in resolution.blocked || second.manifest.id in resolution.blocked)
    }

    @Test
    fun commandEventAndRendererRegistriesAreNamespacedAndRemovable() = runTest {
        val commands = PluginCommandRegistry()
        commands.register(
            PluginCommand("tech.kelma.test", "tech.kelma.test.echo", "Echo") {
                PluginValue.StringValue("ok")
            },
        )
        assertEquals(
            PluginValue.StringValue("ok"),
            commands.invoke(PluginCommandInvocation("tech.kelma.test.echo")),
        )
        val events = PluginEventRegistry()
        var received = false
        events.subscribe("tech.kelma.test", "review.completed") { received = true }
        assertTrue(events.publish(PluginEvent("review.completed")).isEmpty())
        assertTrue(received)

        val renderers = PluginRendererRegistry()
        renderers.register("tech.kelma.test", "tech.kelma.test.wrapper") { request ->
            PluginRenderResult("<b>${request.html}</b>", request.css)
        }
        assertEquals(
            "<b>card</b>",
            renderers.render(PluginRenderRequest("tech.kelma.test.wrapper", "card", "")).html,
        )
        commands.unregisterPlugin("tech.kelma.test")
        events.unregisterPlugin("tech.kelma.test")
        renderers.unregisterPlugin("tech.kelma.test")
        assertTrue(commands.list().isEmpty())
    }

    @Test
    fun rendererOutputIsBoundedBeforeItReachesAWebView() {
        assertFailsWith<IllegalArgumentException> {
            PluginRenderResult("bad\u0000html", "").validated()
        }
        assertFailsWith<IllegalArgumentException> {
            PluginRenderResult("ok", "x".repeat(512 * 1024 + 1)).validated()
        }
    }

    private fun manifest(id: String, version: String, dependencies: List<PluginDependency> = emptyList()) =
        PluginManifest(id, id, version, KelmaPluginApiVersion, "plugin/init.lua", dependencies = dependencies)

    private fun installed(manifest: PluginManifest) =
        InstalledPlugin(manifest.validated(), true, PluginStatus.Installed, null, 1L, 1L)
}
