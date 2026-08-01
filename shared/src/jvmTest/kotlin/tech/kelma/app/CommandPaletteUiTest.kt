package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CommandPaletteUiTest {
    @Test
    fun paletteSearchesBuiltInAndPluginCommandsAndInvokesSelection() = runComposeUiTest {
        val invoked = AtomicReference<String?>(null)
        val registry = PluginCommandRegistry().apply {
            registerKelmaCommands()
            register(
                PluginCommand("tech.kelma.sample", "tech.kelma.sample.hello", "Say hello") {
                    PluginValue.Null
                },
            )
        }
        setContent {
            KelmaTheme {
                CommandPalette(
                    commands = registry.list(),
                    runningCommandId = null,
                    message = null,
                    onInvoke = invoked::set,
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Open Decks").assertIsDisplayed()
        onNodeWithTag("command-palette-search").performTextInput("hello")
        onNodeWithText("Say hello").assertIsDisplayed().performClick()
        assertEquals("tech.kelma.sample.hello", invoked.get())
    }

    @Test
    fun arrowKeysSelectAndEnterExecutesACommand() = runComposeUiTest {
        val invoked = AtomicReference<String?>(null)
        val commands = PluginCommandRegistry().apply { registerKelmaCommands() }.list()
        setContent {
            KelmaTheme {
                CommandPalette(commands, null, null, invoked::set) {}
            }
        }

        onNodeWithTag("command-palette-search").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }

        assertEquals(OpenAddCommand, invoked.get())
    }
}
