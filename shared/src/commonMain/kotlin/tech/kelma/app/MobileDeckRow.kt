package tech.kelma.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val MobileDeckCountWidth = 48.dp

@Composable
internal fun MobileDeckRow(
    deck: DeckSummary,
    depth: Int = 0,
    hasChildren: Boolean = false,
    isCollapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width((depth * 16).dp))
                if (hasChildren) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        IconButton(
                            modifier = Modifier.size(48.dp),
                            onClick = onToggleCollapsed,
                        ) {
                            Icon(
                                imageVector = if (isCollapsed) {
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight
                                } else {
                                    Icons.Rounded.KeyboardArrowDown
                                },
                                contentDescription = if (isCollapsed) {
                                    "Expand deck ${deck.name}"
                                } else {
                                    "Collapse deck ${deck.name}"
                                },
                                tint = KelmaColors.TextSecondary,
                            )
                        }
                    }
                }
                DeckSyncBadgeSlot(deck.pendingChanges)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = deck.name.substringAfterLast("::"),
                    modifier = Modifier.weight(1f),
                    color = KelmaColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MobileDeckCount(deck.newCount, KelmaColors.NewCard)
            MobileDeckCount(deck.learningCount, KelmaColors.Bad)
            MobileDeckCount(deck.dueCount, KelmaColors.Good)
        }
        HorizontalDivider(color = KelmaColors.Hairline)
    }
}

@Composable
private fun MobileDeckCount(value: Int, color: Color) {
    Text(
        text = value.toString(),
        modifier = Modifier.width(MobileDeckCountWidth),
        color = if (value == 0) KelmaColors.TextMuted else color,
        fontSize = 17.sp,
        fontWeight = if (value == 0) FontWeight.Medium else FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
    )
}
