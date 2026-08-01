package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeckPresetControlsUiTest {
    @Test
    fun presetControlsCreateRenameRemoveAndChangeAssignmentExplicitly() = runComposeUiTest {
        val preset = DeckOptionsPreset("preset-1", "Focused", DeckOptions(newCardsPerDay = 40), 1L, 1L)
        val created = AtomicReference<String?>(null)
        val renamed = AtomicReference<Pair<String, String>?>(null)
        val deleted = AtomicReference<String?>(null)
        val assigned = AtomicReference<String?>("unset")
        setContent {
            KelmaTheme {
                DeckPresetControls(
                    state = DeckPresetState(listOf(preset), mapOf("Deck" to preset.id)),
                    deckName = "Deck",
                    desktop = true,
                    currentOptions = { DeckOptions(newCardsPerDay = 50) },
                    onAssign = { _, id -> assigned.set(id); null },
                    onCreate = { _, name, _ -> created.set(name); null },
                    onClone = { _, _, _ -> null },
                    onRename = { id, name -> renamed.set(id to name); null },
                    onDelete = { id -> deleted.set(id); null },
                )
            }
        }

        onNodeWithTag("options-preset-name").performTextClearance()
        onNodeWithTag("options-preset-name").performTextInput("Updated")
        onNodeWithText("Add").performClick()
        waitUntil { created.get() != null }
        assertEquals("Updated", created.get())

        onNodeWithText("Rename").performClick()
        waitUntil { renamed.get() != null }
        assertEquals(preset.id to "Updated", renamed.get())
        onNodeWithText("Remove").performClick()
        waitUntil { deleted.get() != null }
        assertEquals(preset.id, deleted.get())

        onNodeWithTag("options-preset-selector").performClick()
        onNodeWithText("Custom for this deck", useUnmergedTree = true).performClick()
        waitUntil { assigned.get() != "unset" }
        assertEquals(null, assigned.get())
    }
}
