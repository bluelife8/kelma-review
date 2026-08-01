package tech.kelma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors for the shared Add dialogs so they can match either platform palette. */
data class AddDialogPalette(
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val codeSurface: Color,
    val border: Color,
)

@Composable
fun FieldsDialog(notetype: AddNotetype, palette: AddDialogPalette, onDismiss: () -> Unit) {
    AddInfoDialog("Fields", "${notetype.name} · ${notetype.fieldNames.size} fields", palette, onDismiss) {
        notetype.fieldNames.forEachIndexed { index, name ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${index + 1}", color = palette.textMuted, fontSize = 14.sp, modifier = Modifier.width(28.dp))
                Text(name, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun CardsDialog(notetype: AddNotetype, palette: AddDialogPalette, onDismiss: () -> Unit) {
    val templates = NotetypeCatalog.templatesFor(notetype.id)
    AddInfoDialog("Card templates", "${notetype.name} · ${templates.size} cards", palette, onDismiss) {
        templates.forEach { template ->
            Text(
                template.name,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            TemplateBlock("Front template", template.qfmt, palette)
            TemplateBlock("Back template", template.afmt, palette)
        }
    }
}

@Composable
fun EditorHelpDialog(palette: AddDialogPalette, onDismiss: () -> Unit) {
    AddInfoDialog("Editor help", "Formatting and shortcuts", palette, onDismiss) {
        HelpLine("Type", "Choose the note type; each one generates its own cards.", palette)
        HelpLine("Deck", "Pick an existing deck or type a new name to create one.", palette)
        HelpLine("Toolbar", "Bold, italic, underline, super/subscript, color, lists, alignment and math.", palette)
        HelpLine("Pin a field", "Keeps its contents after adding, for entering related cards quickly.", palette)
        HelpLine("Preview", "Shows how a field's formatting will look on the card.", palette)
        HelpLine("Add", "Ctrl or Cmd + Enter adds the note without leaving the keyboard.", palette)
    }
}

@Composable
fun HistoryDialog(history: List<String>, palette: AddDialogPalette, onDismiss: () -> Unit) {
    AddInfoDialog("History", "Added this session", palette, onDismiss) {
        if (history.isEmpty()) {
            Text("Nothing added yet.", color = palette.textMuted, fontSize = 14.sp)
        } else {
            history.forEach { entry ->
                Text(
                    entry,
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun AddInfoDialog(
    title: String,
    subtitle: String,
    palette: AddDialogPalette,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = palette.accent, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Column {
                Text(title, color = palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = palette.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                content()
            }
        },
    )
}

@Composable
private fun TemplateBlock(label: String, code: String, palette: AddDialogPalette) {
    Text(label, color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.codeSurface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            code,
            modifier = Modifier.padding(12.dp),
            color = palette.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun HelpLine(term: String, description: String, palette: AddDialogPalette) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(term, color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(description, color = palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
    }
}
