package tech.kelma.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewMoreKeybindingsTest {
    @Test
    fun everyDisplayedShortcutMapsToItsAction() {
        ReviewFlag.entries.forEach { flag ->
            assertEquals(
                ReviewMenuCommand.Flag(flag),
                reviewMenuCommandForKey(numberKey(flag.value), command = true),
            )
        }

        val actions = listOf(
            binding(Key.Minus, ReviewMoreAction.BuryCard),
            binding(Key.N, ReviewMoreAction.ResetCard, command = true, alt = true),
            binding(Key.D, ReviewMoreAction.SetDueDate, command = true, shift = true),
            binding(Key.Two, ReviewMoreAction.SuspendCard, shift = true),
            binding(Key.O, ReviewMoreAction.Options),
            binding(Key.I, ReviewMoreAction.CardInfo),
            binding(Key.I, ReviewMoreAction.PreviousCardInfo, command = true, alt = true),
            binding(Key.Eight, ReviewMoreAction.MarkNote, shift = true),
            binding(Key.Equals, ReviewMoreAction.BuryNote),
            binding(Key.One, ReviewMoreAction.SuspendNote, shift = true),
            binding(Key.E, ReviewMoreAction.CreateCopy, command = true, alt = true),
            binding(Key.Backspace, ReviewMoreAction.DeleteNote, command = true),
            binding(Key.R, ReviewMoreAction.ReplayAudio),
            binding(Key.Five, ReviewMoreAction.PauseAudio),
            binding(Key.Six, ReviewMoreAction.AudioBackFive),
            binding(Key.Seven, ReviewMoreAction.AudioForwardFive),
            binding(Key.V, ReviewMoreAction.RecordOwnVoice, shift = true),
            binding(Key.V, ReviewMoreAction.ReplayOwnVoice),
            binding(Key.A, ReviewMoreAction.AutoAdvance, shift = true),
        )
        actions.forEach { binding ->
            assertEquals(
                ReviewMenuCommand.Action(binding.action),
                reviewMenuCommandForKey(
                    binding.key,
                    command = binding.command,
                    alt = binding.alt,
                    shift = binding.shift,
                ),
            )
        }
    }
}

private data class KeyBinding(
    val key: Key,
    val action: ReviewMoreAction,
    val command: Boolean,
    val alt: Boolean,
    val shift: Boolean,
)

private fun binding(
    key: Key,
    action: ReviewMoreAction,
    command: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
): KeyBinding = KeyBinding(key, action, command, alt, shift)

private fun numberKey(value: Int): Key = when (value) {
    0 -> Key.Zero
    1 -> Key.One
    2 -> Key.Two
    3 -> Key.Three
    4 -> Key.Four
    5 -> Key.Five
    6 -> Key.Six
    7 -> Key.Seven
    else -> error("Unsupported number key")
}
