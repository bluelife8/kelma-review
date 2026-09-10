package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class MobileDeckMenuOption(
    val name: String,
    val label: String,
    val depth: Int,
    val cardCount: Int,
)

@Composable
internal fun MobileDeckFilterPicker(
    options: List<DeckPickerOption>,
    selectedName: String?,
    testTagPrefix: String,
    onSelectName: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiers = remember(options, selectedName) { deckPickerTiers(options, selectedName) }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        MobileDeckPickerField(
            label = "DECK",
            selectedLabel = tiers.selectedDeck ?: "All decks",
            allLabel = "All decks",
            allValue = null,
            options = tiers.decks.map { MobileDeckMenuOption(it.name, it.name, 0, it.cardCount) },
            active = tiers.selectedDeck != null,
            testTag = "$testTagPrefix-deck-picker",
            itemTagPrefix = "$testTagPrefix-deck",
            onSelect = onSelectName,
        )
        val selectedDeck = tiers.selectedDeck
        if (selectedDeck != null && tiers.subdecks.isNotEmpty()) {
            MobileDeckPickerField(
                label = "SUBDECK",
                selectedLabel = tiers.selectedSubdeck?.substringAfter("$selectedDeck::") ?: "All",
                allLabel = "All",
                allValue = selectedDeck,
                options = tiers.subdecks.map { option ->
                    MobileDeckMenuOption(
                        name = option.name,
                        label = option.name.substringAfter("$selectedDeck::"),
                        depth = (option.depth - 1).coerceAtLeast(0),
                        cardCount = option.cardCount,
                    )
                },
                active = tiers.selectedSubdeck != null,
                testTag = "$testTagPrefix-subdeck-picker",
                itemTagPrefix = "$testTagPrefix-subdeck",
                onSelect = onSelectName,
            )
        }
    }
}

@Composable
private fun MobileDeckPickerField(
    label: String,
    selectedLabel: String,
    allLabel: String,
    allValue: String?,
    options: List<MobileDeckMenuOption>,
    active: Boolean,
    testTag: String,
    itemTagPrefix: String,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
            color = KelmaColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.testTag(testTag),
                color = if (active) KelmaColors.Gold.copy(alpha = 0.12f) else KelmaColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (active) KelmaColors.Gold else KelmaColors.SurfaceBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedLabel,
                        modifier = Modifier.weight(1f),
                        color = if (active) KelmaColors.GoldSoft else KelmaColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "Choose ${label.lowercase()}",
                        tint = KelmaColors.TextSecondary,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(allLabel) },
                    modifier = Modifier.testTag("$itemTagPrefix-all"),
                    onClick = {
                        expanded = false
                        onSelect(allValue)
                    },
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width((option.depth * 12).dp))
                                Text(
                                    option.label,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(option.cardCount.toString(), color = KelmaColors.TextMuted, fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.testTag("$itemTagPrefix-${option.name}"),
                        onClick = {
                            expanded = false
                            onSelect(option.name)
                        },
                    )
                }
            }
        }
    }
}
