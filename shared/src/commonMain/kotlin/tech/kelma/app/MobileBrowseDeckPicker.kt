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

@Composable
internal fun MobileBrowseDeckPicker(
    selectedDeck: String?,
    decks: List<Pair<String, Int>>,
    onSelectDeck: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(
            "DECK",
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
            color = KelmaColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                    .testTag("mobile-browse-deck-picker"),
                color = if (selectedDeck == null) KelmaColors.Surface else KelmaColors.Gold.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (selectedDeck == null) KelmaColors.SurfaceBorder else KelmaColors.Gold,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedDeck ?: "All decks",
                        modifier = Modifier.weight(1f),
                        color = if (selectedDeck == null) KelmaColors.TextPrimary else KelmaColors.GoldSoft,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "Choose Browse deck",
                        tint = KelmaColors.TextSecondary,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("All decks") },
                    onClick = {
                        expanded = false
                        onSelectDeck(null)
                    },
                )
                decks.forEach { (name, count) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width((deckHierarchyNames(name).lastIndex * 12).dp))
                                Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(count.toString(), color = KelmaColors.TextMuted, fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.testTag("mobile-browse-deck-$name"),
                        onClick = {
                            expanded = false
                            onSelectDeck(name)
                        },
                    )
                }
            }
        }
    }
}
