package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReviewHeader(
    deck: DeckSummary,
    undoEnabled: Boolean,
    moreEnabled: Boolean = false,
    currentFlag: Int = 0,
    currentNoteMarked: Boolean = false,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onMenuCommand: (ReviewMenuCommand) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var moreExpanded by remember(deck.id) { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(
                text = "‹",
                color = KelmaColors.GoldSoft,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = deck.name,
                color = KelmaColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onUndo, enabled = undoEnabled) {
            Icon(
                Icons.AutoMirrored.Rounded.Undo,
                contentDescription = "Undo last review",
                tint = if (undoEnabled) KelmaColors.GoldSoft else KelmaColors.TextMuted,
            )
        }
        Box {
            IconButton(
                onClick = { moreExpanded = true },
                enabled = moreEnabled,
                modifier = Modifier.testTag("mobile-review-more-button"),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Card options",
                    tint = if (moreEnabled) KelmaColors.GoldSoft else KelmaColors.TextMuted,
                )
            }
            MobileReviewMoreMenu(
                expanded = moreExpanded && moreEnabled,
                currentFlag = currentFlag,
                currentNoteMarked = currentNoteMarked,
                onDismiss = { moreExpanded = false },
                onCommand = onMenuCommand,
            )
        }
        CountPill(deck.newCount, KelmaColors.NewCard)
        Spacer(Modifier.width(5.dp))
        CountPill(deck.learningCount, KelmaColors.Bad)
        Spacer(Modifier.width(5.dp))
        CountPill(deck.dueCount, KelmaColors.Good)
    }
}

@Composable
private fun CountPill(value: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = value.toString(),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
internal fun DesktopReviewFooter(
    deck: DeckSummary,
    showingAnswer: Boolean,
    savingReview: Boolean,
    canUndo: Boolean,
    ratingIntervals: Map<Rating, String>,
    cardFlag: Int = 0,
    noteMarked: Boolean = false,
    autoAdvanceEnabled: Boolean = false,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onUndo: () -> Unit,
    moreExpanded: Boolean = false,
    onMoreExpandedChange: (Boolean) -> Unit = {},
    onMoreAction: (ReviewMoreAction) -> Unit = {},
    onFlag: (ReviewFlag) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().height(82.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.height(23.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterCount(deck.newCount, KelmaDesktopColors.New)
            Text(" + ", color = KelmaDesktopColors.TextMuted, fontSize = 12.sp)
            FooterCount(deck.learningCount, KelmaDesktopColors.Learn)
            Text(" + ", color = KelmaDesktopColors.TextMuted, fontSize = 12.sp)
            FooterCount(deck.dueCount, KelmaDesktopColors.Due)
            if (cardFlag in 1..7) {
                Text(
                    "⚑",
                    modifier = Modifier.padding(start = 8.dp),
                    color = reviewFlagColor(cardFlag),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (canUndo && !savingReview) {
                DesktopUtilityButton(
                    label = "Undo",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                    width = 84.dp,
                    onClick = onUndo,
                )
            }
            if (savingReview) {
                Text("Working…", color = KelmaDesktopColors.TextMuted, fontSize = 12.sp)
            } else if (showingAnswer) {
                RatingButtons(
                    desktopLayout = true,
                    ratingIntervals = ratingIntervals,
                    onRate = onRate,
                )
            } else {
                DesktopGoldButton(
                    "Show Answer",
                    width = 300.dp,
                    height = 40.dp,
                    modifier = Modifier.focusProperties { canFocus = false },
                    shortcut = "Space",
                    onClick = onReveal,
                )
            }
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                DesktopUtilityButton(
                    label = "More",
                    width = 84.dp,
                    showMenuArrow = true,
                    onClick = { onMoreExpandedChange(true) },
                )
                DesktopReviewMoreMenu(
                    expanded = moreExpanded,
                    onDismiss = { onMoreExpandedChange(false) },
                    onAction = { action ->
                        onMoreExpandedChange(false)
                        onMoreAction(action)
                    },
                    currentFlag = cardFlag,
                    currentNoteMarked = noteMarked,
                    autoAdvanceEnabled = autoAdvanceEnabled,
                    onFlag = { flag ->
                        onMoreExpandedChange(false)
                        onFlag(flag)
                    },
                )
            }
        }
    }
}

@Composable
private fun FooterCount(value: Int, color: Color) {
    Text(value.toString(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun MobileReviewControls(
    showingAnswer: Boolean,
    savingReview: Boolean,
    ratingIntervals: Map<Rating, String>,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (savingReview) {
            Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (showingAnswer) {
            Text(
                "Tap card: left = Again · right = Good",
                modifier = Modifier.padding(bottom = 8.dp),
                color = KelmaColors.TextMuted,
                fontSize = 12.sp,
            )
            RatingButtons(
                desktopLayout = false,
                ratingIntervals = ratingIntervals,
                onRate = onRate,
            )
        } else {
            Button(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KelmaColors.Surface,
                    contentColor = KelmaColors.GoldSoft,
                ),
                border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
                shape = RoundedCornerShape(14.dp),
                onClick = onReveal,
            ) {
                Text("Show answer", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun RatingButtons(
    desktopLayout: Boolean,
    ratingIntervals: Map<Rating, String>,
    onRate: (Rating) -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = if (desktopLayout) 620.dp else 640.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Rating.entries.forEach { rating ->
            val colors = ratingButtonColors(rating)
            Button(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (desktopLayout) {
                            Modifier
                                .focusProperties { canFocus = false }
                                .pointerHoverIcon(PointerIcon.Hand)
                        } else {
                            Modifier
                        },
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.container,
                    contentColor = colors.content,
                ),
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(
                    horizontal = if (desktopLayout) 16.dp else 6.dp,
                    vertical = if (desktopLayout) 8.dp else 12.dp,
                ),
                onClick = { onRate(rating) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ratingIntervals[rating]?.let { interval ->
                        Text(
                            interval,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.content.copy(alpha = 0.72f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (desktopLayout) {
                            Text(
                                (rating.ordinal + 1).toString(),
                                modifier = Modifier.padding(end = 6.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.content.copy(alpha = 0.68f),
                            )
                        }
                        Text(
                            rating.label,
                            fontSize = if (desktopLayout) 14.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
