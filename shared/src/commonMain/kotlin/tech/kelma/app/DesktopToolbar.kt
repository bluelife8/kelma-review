package tech.kelma.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalOpenStats = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun DesktopTopToolbar(
    onDecks: () -> Unit,
    onSync: () -> Unit,
    onAdd: () -> Unit = {},
    onBrowse: () -> Unit = {},
    onOptions: () -> Unit = {},
    syncing: Boolean = false,
    activeItem: String? = null,
) {
    val onStats = LocalOpenStats.current
    Box(
        modifier = Modifier.fillMaxWidth().height(82.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(510.dp).height(44.dp),
            color = KelmaDesktopColors.Toolbar,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, KelmaDesktopColors.Border),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopToolbarItem("Decks", onDecks, selected = activeItem == "Decks")
                DesktopToolbarItem("Add", onAdd, selected = activeItem == "Add")
                DesktopToolbarItem("Browse", onBrowse, selected = activeItem == "Browse")
                DesktopToolbarItem("Options", onOptions, selected = activeItem == "Options")
                DesktopToolbarItem("Stats", onStats, selected = activeItem == "Stats")
                DesktopToolbarItem(
                    "Sync",
                    onSync,
                    loading = syncing,
                    selected = activeItem == "Sync",
                )
            }
        }
    }
}

@Composable
private fun DesktopToolbarItem(
    label: String,
    onClick: (() -> Unit)? = null,
    loading: Boolean = false,
    selected: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val selectionState = selected
    val hovered by interactions.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> KelmaDesktopColors.Gold.copy(alpha = 0.14f)
            hovered -> KelmaDesktopColors.SurfaceHigh
            else -> androidx.compose.ui.graphics.Color.Transparent
        },
    )
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(if (label in setOf("Browse", "Options")) 88.dp else 74.dp)
            .background(background, RoundedCornerShape(18.dp))
            .semantics { this.selected = selectionState }
            .hoverable(interactions)
            .then(
                if (onClick == null) Modifier else Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interactions,
                        indication = null,
                        onClick = onClick,
                    ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.width(16.dp).height(16.dp),
                color = KelmaDesktopColors.Accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = label,
                color = if (selected || hovered) KelmaDesktopColors.Gold else KelmaDesktopColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
