package tech.kelma.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PluginManagerUiTest {
    @Test
    fun rendererCanBeAssignedToADeck() = runComposeUiTest {
        val assigned = AtomicReference<Triple<PluginRendererScope, String, String>?>(null)
        setContent {
            KelmaTheme {
                PluginRendererAssignmentControls(
                    assignments = PluginRendererAssignmentState(),
                    rendererIds = listOf("tech.kelma.ui.renderer"),
                    deckNames = listOf("Japanese"),
                    noteTypes = listOf(RendererNoteTypeTarget(100, "Basic")),
                    busy = false,
                    onAssign = { scope, target, renderer -> assigned.set(Triple(scope, target, renderer)) },
                    onRemove = { _, _ -> },
                )
            }
        }

        onNodeWithTag("assign-renderer").performClick()
        onNodeWithText("Assign").performClick()
        assertEquals(
            Triple(PluginRendererScope.Deck, "Japanese", "tech.kelma.ui.renderer"),
            assigned.get(),
        )
    }

    @Test
    fun managerShowsRuntimeStateAndRequiresCapabilityConfirmation() = runComposeUiTest {
        val manifest = PluginManifest(
            id = "tech.kelma.ui",
            name = "UI sample",
            version = "1.2.3",
            apiVersion = KelmaPluginApiVersion,
            entrypoint = "plugin/init.lua",
            capabilities = setOf(PluginCapability.Commands, PluginCapability.Network),
        )
        val installed = InstalledPlugin(manifest, true, PluginStatus.Installed, null, 1L, 2L)
        val enabled = AtomicReference<Pair<String, Boolean>?>(null)
        val safeMode = AtomicBoolean(false)
        val command = AtomicReference<String?>(null)
        val confirmed = AtomicBoolean(false)
        val pending = mutableStateOf<PluginManifest?>(manifest)
        setContent {
            KelmaTheme {
                PluginManagerScreen(
                    state = PluginHostState(
                        installed = listOf(installed),
                        running = listOf(
                            RunningPlugin(
                                manifest.id,
                                4,
                                listOf(PluginRuntimeCommand("tech.kelma.ui.run", "Sample command")),
                                setOf("app.started"),
                                emptySet(),
                            ),
                        ),
                    ),
                    busy = false,
                    message = null,
                    rendererAssignments = PluginRendererAssignmentState(),
                    rendererIds = emptyList(),
                    deckNames = listOf("Deck"),
                    noteTypes = emptyList(),
                    onDecks = {},
                    onAdd = {},
                    onBrowse = {},
                    onOptions = {},
                    onSync = {},
                    onInstall = {},
                    pendingInstall = pending.value,
                    onConfirmInstall = { pending.value = null; confirmed.set(true) },
                    onDismissInstall = {},
                    onReload = {},
                    onSafeMode = safeMode::set,
                    onEnabled = { id, value -> enabled.set(id to value) },
                    onUninstall = {},
                    onRunCommand = command::set,
                    onAssignRenderer = { _, _, _ -> },
                    onRemoveRenderer = { _, _ -> },
                    onLoadLogs = { emptyList() },
                )
            }
        }

        onNodeWithText("Requested capabilities:").assertExists()
        onNodeWithText("Commands, Network").assertExists()
        onNodeWithText("Install").performClick()
        assertTrue(confirmed.get())

        onNodeWithText("UI sample").assertExists()
        onNodeWithText("Running · 4 ms startup").assertExists()
        onNodeWithText("Run Sample command").performClick()
        assertEquals("tech.kelma.ui.run", command.get())
        onNodeWithTag("plugin-enabled-${manifest.id}").performClick()
        assertEquals(manifest.id to false, enabled.get())
        onNodeWithTag("plugin-safe-mode").performClick()
        assertTrue(safeMode.get())
    }
}
