package tech.kelma.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DesktopUtilityButton(
    label: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    showMenuArrow: Boolean = false,
    onClick: () -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered) KelmaDesktopColors.TextMuted else KelmaDesktopColors.UtilityButton,
    )
    Surface(
        modifier = modifier
            .then(if (width == null) Modifier else Modifier.width(width))
            .height(28.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = color,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (showMenuArrow) 7.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = KelmaDesktopColors.TextPrimary, fontSize = 12.sp)
            if (showMenuArrow) {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = KelmaDesktopColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
fun DesktopGoldButton(
    label: String,
    width: Dp,
    height: Dp = 48.dp,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered) KelmaColors.GoldBright else KelmaDesktopColors.Gold,
    )
    Surface(
        modifier = modifier
            .width(width)
            .height(height)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = color,
        contentColor = KelmaDesktopColors.Background,
        shape = RoundedCornerShape(height / 2),
        shadowElevation = if (hovered) 11.dp else 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            shortcut?.let {
                Text(
                    it,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = KelmaDesktopColors.Background.copy(alpha = 0.62f),
                )
            }
        }
    }
}

@Composable
fun DesktopIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered) KelmaDesktopColors.SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent,
    )
    Surface(
        modifier = modifier
            .size(30.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = color,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = KelmaDesktopColors.TextSecondary,
            )
        }
    }
}
