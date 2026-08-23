package tech.kelma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * In-place note editor shown during review. It replaces the card surface itself (never a modal)
 * with the same [BrowseInlineEditor] Browse uses, styled per platform family.
 */
@Composable
internal fun ReviewNoteEditor(
    target: BrowseEditTarget,
    onAttach: suspend (PickedMediaFile) -> String,
    onSave: suspend (BrowseNoteEdit) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    desktop: Boolean = isDesktopApp,
) {
    val scroll = rememberScrollState()
    if (desktop) {
        Box(
            modifier = modifier.fillMaxSize().testTag("review-note-editor"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                BrowseInlineEditor(
                    target = target,
                    titleColor = KelmaDesktopColors.TextMuted,
                    textSecondary = KelmaDesktopColors.TextSecondary,
                    accent = KelmaDesktopColors.Gold,
                    surfaceColor = KelmaDesktopColors.Surface,
                    borderColor = KelmaDesktopColors.Border,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = 14.dp,
                    onAttach = onAttach,
                    onSave = onSave,
                    onSaved = onClose,
                    onCancel = onClose,
                )
            }
        }
    } else {
        var inputFocused by remember(target.row.noteGuid) { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        Box(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .testTag("review-note-editor"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .platformPointerScroll(scroll)
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = if (inputFocused) 52.dp else 0.dp),
            ) {
                BrowseInlineEditor(
                    target = target,
                    titleColor = KelmaColors.TextMuted,
                    textSecondary = KelmaColors.TextSecondary,
                    accent = KelmaColors.Gold,
                    surfaceColor = KelmaColors.Surface,
                    borderColor = KelmaColors.SurfaceBorder,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = 14.dp,
                    onAttach = onAttach,
                    onSave = onSave,
                    onSaved = onClose,
                    onCancel = onClose,
                    onInputFocusChanged = { inputFocused = it },
                )
            }
            if (inputFocused) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .testTag("review-editor-keyboard-toolbar"),
                    color = KelmaColors.Surface,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { focusManager.clearFocus() },
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .testTag("review-editor-keyboard-done"),
                        ) {
                            Text("Done", color = KelmaColors.Gold, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
