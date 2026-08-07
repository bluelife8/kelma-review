package tech.kelma.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal fun ReviewShortcut.answerRating(): Rating = when (this) {
    ReviewShortcut.Space, ReviewShortcut.Three -> Rating.Good
    ReviewShortcut.One -> Rating.Again
    ReviewShortcut.Two -> Rating.Hard
    ReviewShortcut.Four -> Rating.Easy
}

internal fun KeyEvent.toReviewMenuCommand(): ReviewMenuCommand? {
    if (type != KeyEventType.KeyUp) return null
    return reviewMenuCommandForKey(
        key = key,
        command = isMetaPressed || isCtrlPressed,
        alt = isAltPressed,
        shift = isShiftPressed,
    )
}

internal fun reviewMenuCommandForKey(
    key: Key,
    command: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
): ReviewMenuCommand? {
    if (command && !alt && !shift) {
        val flag = when (key) {
            Key.Zero -> ReviewFlag.None
            Key.One -> ReviewFlag.Red
            Key.Two -> ReviewFlag.Orange
            Key.Three -> ReviewFlag.Green
            Key.Four -> ReviewFlag.Blue
            Key.Five -> ReviewFlag.Pink
            Key.Six -> ReviewFlag.Turquoise
            Key.Seven -> ReviewFlag.Purple
            else -> null
        }
        if (flag != null) return ReviewMenuCommand.Flag(flag)
    }
    val action = when {
        command && alt && key == Key.N -> ReviewMoreAction.ResetCard
        command && shift && key == Key.D -> ReviewMoreAction.SetDueDate
        command && alt && key == Key.I -> ReviewMoreAction.PreviousCardInfo
        command && alt && key == Key.E -> ReviewMoreAction.CreateCopy
        command && key == Key.Backspace -> ReviewMoreAction.DeleteNote
        !command && !alt && shift && key == Key.Two -> ReviewMoreAction.SuspendCard
        !command && !alt && shift && key == Key.Eight -> ReviewMoreAction.MarkNote
        !command && !alt && shift && key == Key.One -> ReviewMoreAction.SuspendNote
        !command && !alt && shift && key == Key.V -> ReviewMoreAction.RecordOwnVoice
        !command && !alt && shift && key == Key.A -> ReviewMoreAction.AutoAdvance
        !command && !alt && !shift && key == Key.E -> ReviewMoreAction.EditNote
        !command && !alt && !shift && key == Key.Minus -> ReviewMoreAction.BuryCard
        !command && !alt && !shift && key == Key.Equals -> ReviewMoreAction.BuryNote
        !command && !alt && !shift && key == Key.O -> ReviewMoreAction.Options
        !command && !alt && !shift && key == Key.I -> ReviewMoreAction.CardInfo
        !command && !alt && !shift && key == Key.R -> ReviewMoreAction.ReplayAudio
        !command && !alt && !shift && key == Key.Five -> ReviewMoreAction.PauseAudio
        !command && !alt && !shift && key == Key.Six -> ReviewMoreAction.AudioBackFive
        !command && !alt && !shift && key == Key.Seven -> ReviewMoreAction.AudioForwardFive
        !command && !alt && !shift && key == Key.V -> ReviewMoreAction.ReplayOwnVoice
        else -> null
    }
    return action?.let(ReviewMenuCommand::Action)
}

internal fun KeyEvent.toReviewShortcut(): ReviewShortcut? {
    if (type != KeyEventType.KeyUp) return null
    return when (key) {
        Key.Spacebar -> ReviewShortcut.Space
        Key.One -> ReviewShortcut.One
        Key.Two -> ReviewShortcut.Two
        Key.Three -> ReviewShortcut.Three
        Key.Four -> ReviewShortcut.Four
        else -> null
    }
}
