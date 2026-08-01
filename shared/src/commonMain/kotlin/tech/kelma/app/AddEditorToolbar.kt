package tech.kelma.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatClear
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Subscript
import androidx.compose.material.icons.rounded.Superscript
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors that let the shared toolbar match either the desktop or mobile palette. */
data class AddToolbarPalette(
    val icon: Color,
    val hover: Color,
    val surface: Color,
    val border: Color,
    val accent: Color,
    val menuSurface: Color,
    val menuText: Color,
)

private val TextColors = listOf(
    "#f4f1e7", "#e0b062", "#86bef4", "#18c45a", "#ff6b73", "#dcc48f", "#ffa500", "#c58bff",
)
private val HighlightColors = listOf(
    "#5b532b", "#294a2f", "#4a2b2f", "#2b3a4a", "#3a3d31",
)

/**
 * The formatting toolbar. It edits the currently focused field by applying pure [HtmlEditing]
 * transforms, so the same control set drives desktop and mobile.
 */
@Composable
fun AddFormatToolbar(
    state: AddUiState,
    actions: AddActions,
    palette: AddToolbarPalette,
    onFields: () -> Unit,
    onCards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun apply(transform: (String, Int, Int) -> HtmlEditing.Edit) {
        val value = state.focused.value
        val edit = transform(value.text, value.selection.start, value.selection.end)
        actions.onFieldChange(
            state.focusedField,
            TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)),
        )
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextTool("Fields…", palette, onFields)
        TextTool("Cards…", palette, onCards)
        ToolDivider(palette)
        Tool(Icons.Rounded.FormatBold, "Bold", palette) { apply { t, s, e -> HtmlEditing.wrap(t, s, e, "<b>", "</b>") } }
        Tool(Icons.Rounded.FormatItalic, "Italic", palette) { apply { t, s, e -> HtmlEditing.wrap(t, s, e, "<i>", "</i>") } }
        Tool(Icons.Rounded.FormatUnderlined, "Underline", palette) {
            apply { t, s, e -> HtmlEditing.wrap(t, s, e, "<u>", "</u>") }
        }
        Tool(Icons.Rounded.Superscript, "Superscript", palette) {
            apply { t, s, e -> HtmlEditing.wrap(t, s, e, "<sup>", "</sup>") }
        }
        Tool(Icons.Rounded.Subscript, "Subscript", palette) {
            apply { t, s, e -> HtmlEditing.wrap(t, s, e, "<sub>", "</sub>") }
        }
        SwatchTool(Icons.Rounded.FormatColorText, "Text color", TextColors, palette) { hex ->
            apply { t, s, e -> HtmlEditing.span(t, s, e, "color:$hex") }
        }
        SwatchTool(Icons.Rounded.FormatColorFill, "Highlight", HighlightColors, palette) { hex ->
            apply { t, s, e -> HtmlEditing.span(t, s, e, "background-color:$hex") }
        }
        Tool(Icons.Rounded.FormatClear, "Remove formatting", palette) {
            apply { t, s, e -> HtmlEditing.clearFormatting(t, s, e) }
        }
        ToolDivider(palette)
        Tool(Icons.Rounded.Image, "Attach image", palette, actions.onAttachImage)
        Tool(Icons.Rounded.Audiotrack, "Attach audio", palette, actions.onAttachAudio)
        ToolDivider(palette)
        Tool(Icons.AutoMirrored.Rounded.FormatListBulleted, "Bulleted list", palette) {
            apply { t, s, e -> HtmlEditing.list(t, s, e, ordered = false) }
        }
        Tool(Icons.Rounded.FormatListNumbered, "Numbered list", palette) {
            apply { t, s, e -> HtmlEditing.list(t, s, e, ordered = true) }
        }
        AlignTool(palette) { alignment -> apply { t, s, e -> HtmlEditing.align(t, s, e, alignment) } }
        ToolDivider(palette)
        MathTool(palette) { prefix, suffix -> apply { t, s, e -> HtmlEditing.wrap(t, s, e, prefix, suffix) } }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, palette: AddToolbarPalette, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) palette.hover else Color.Transparent)
    Surface(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(34.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = palette.icon, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TextTool(label: String, palette: AddToolbarPalette, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) palette.hover else palette.surface)
    Surface(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, palette.border),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(label, color = palette.icon, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SwatchTool(
    icon: ImageVector,
    label: String,
    swatches: List<String>,
    palette: AddToolbarPalette,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tool(icon, label, palette) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(150.dp),
        ) {
            Text(
                label,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
                color = palette.menuText.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                swatches.forEach { hex ->
                    Surface(
                        modifier = Modifier
                            .padding(3.dp)
                            .size(22.dp)
                            .clickable {
                                onPick(hex)
                                expanded = false
                            },
                        color = parseSwatch(hex),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, palette.border),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun AlignTool(palette: AddToolbarPalette, onAlign: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tool(Icons.AutoMirrored.Rounded.FormatAlignLeft, "Alignment", palette) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlignItem(Icons.AutoMirrored.Rounded.FormatAlignLeft, "Left", palette) { onAlign("left"); expanded = false }
            AlignItem(Icons.Rounded.FormatAlignCenter, "Center", palette) { onAlign("center"); expanded = false }
            AlignItem(Icons.AutoMirrored.Rounded.FormatAlignRight, "Right", palette) { onAlign("right"); expanded = false }
        }
    }
}

@Composable
private fun AlignItem(icon: ImageVector, label: String, palette: AddToolbarPalette, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = palette.menuText, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = palette.icon, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun MathTool(palette: AddToolbarPalette, onInsert: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tool(Icons.Rounded.Functions, "Math and equations", palette) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Inline math  \\( \\)", color = palette.menuText, fontSize = 14.sp) },
                onClick = { onInsert("\\(", "\\)"); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Block math  \\[ \\]", color = palette.menuText, fontSize = 14.sp) },
                onClick = { onInsert("\\[", "\\]"); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("LaTeX  [latex]", color = palette.menuText, fontSize = 14.sp) },
                onClick = { onInsert("[latex]", "[/latex]"); expanded = false },
            )
        }
    }
}

@Composable
private fun ToolDivider(palette: AddToolbarPalette) {
    Surface(
        color = palette.border,
        modifier = Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp),
    ) {}
}

private fun parseSwatch(hex: String): Color {
    val value = hex.removePrefix("#").toIntOrNull(16) ?: return Color.Gray
    return Color((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
}
