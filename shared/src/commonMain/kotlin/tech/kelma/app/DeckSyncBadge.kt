package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeckSyncBadgeSlot(
    changes: PendingDeckChanges,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.widthIn(min = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        DeckSyncBadge(changes)
    }
}

@Composable
internal fun DeckSyncBadge(
    changes: PendingDeckChanges,
    modifier: Modifier = Modifier,
) {
    val synced = changes.added == 0 && changes.changed == 0
    val label = buildList {
        if (changes.added > 0) add("+${changes.added}")
        if (changes.changed > 0) add("~${changes.changed}")
    }.joinToString(" ")
    val pendingWidth = (label.length * 6 + 10).coerceIn(32, 64).dp
    Surface(
        modifier = modifier.then(
            Modifier.size(
                width = if (synced) 22.dp else pendingWidth,
                height = 20.dp,
            ),
        ),
        color = Color.Transparent,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Accent),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (synced) 0.dp else 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (synced) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = KelmaDesktopColors.Accent,
                )
            } else {
                Text(
                    text = label,
                    modifier = Modifier.offset(y = (-1).dp),
                    color = KelmaDesktopColors.Accent,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
