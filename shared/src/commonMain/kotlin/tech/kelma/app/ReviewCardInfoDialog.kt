package tech.kelma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

@Composable
internal fun ReviewCardInfoDialog(
    info: ReviewCardInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Card Info", fontWeight = FontWeight.SemiBold)
                Text(
                    info.deckName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CardInfoStatusRow(info)
                CardInfoSection("SCHEDULE") {
                    CardInfoLine("State", info.state)
                    CardInfoLine("Due", info.dueAtMillis?.let(::formatCardInfoTimestamp) ?: "Not scheduled")
                    CardInfoLine("Last review", info.lastReviewAtMillis?.let(::formatCardInfoTimestamp) ?: "Never")
                    CardInfoLine("Interval", info.scheduledDays?.let(::formatCardInfoDays) ?: "—")
                    CardInfoLine("Reviews", info.repetitions.toString())
                    CardInfoLine("Lapses", info.lapses.toString())
                }
                CardInfoSection("FSRS") {
                    CardInfoLine("Scheduler", info.scheduler)
                    CardInfoLine(
                        "Profile",
                        if (info.schedulerProfileVersion > 0L) "Local v${info.schedulerProfileVersion}" else "Local default",
                    )
                    CardInfoLine(
                        "Cloud",
                        info.cloudSchedulerProfileVersion?.let { "v$it · ${info.schedulerProfileStatus}" }
                            ?: "Not published · ${info.schedulerProfileStatus}",
                    )
                    CardInfoLine("Desired retention", "${(info.desiredRetention * 100).roundToInt()}%")
                    CardInfoLine("Stability", info.stability?.let { formatCardInfoDecimal(it, " days") } ?: "—")
                    CardInfoLine("Difficulty", info.difficulty?.let { formatCardInfoDecimal(it) } ?: "—")
                }
                CardInfoSection("NOTE") {
                    CardInfoLine("Note type", info.notetypeName)
                    CardInfoLine("Template", "Card ${info.templateOrdinal + 1}")
                    CardInfoLine("Tags", info.tags.takeIf(List<String>::isNotEmpty)?.joinToString("  ") ?: "None")
                    CardInfoLine("Note ID", info.noteGuid, monospace = true)
                    CardInfoLine("Card ID", info.cardId.toString(), monospace = true)
                    CardInfoLine("Created", info.createdAtMillis?.let(::formatCardInfoTimestamp) ?: "Unknown")
                }
                CardInfoHistory(info.recentReviews)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun CardInfoStatusRow(info: ReviewCardInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardInfoBadge(info.state)
        if (info.noteMarked) CardInfoBadge("Marked")
        if (info.flag in 1..7) {
            CardInfoBadge(
                text = "Flag ${info.flag}",
                color = reviewFlagColor(info.flag),
            )
        }
    }
}

@Composable
private fun CardInfoBadge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        color = color.copy(alpha = 0.13f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CardInfoSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun CardInfoLine(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            modifier = Modifier.weight(0.62f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun CardInfoHistory(reviews: List<ReviewHistoryInfo>) {
    CardInfoSection("RECENT REVIEWS") {
        if (reviews.isEmpty()) {
            Text(
                "No review history yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            reviews.forEach { review ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            formatCardInfoTimestamp(review.reviewedAtMillis),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (review.pendingSync) {
                            Text(
                                "Awaiting sync",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(
                        review.rating.label,
                        color = ratingButtonColors(review.rating).content,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        review.interval,
                        modifier = Modifier.weight(0.32f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatCardInfoTimestamp(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.year}-${(local.month.ordinal + 1).twoDigits()}-${local.day.twoDigits()} " +
        "${local.hour.twoDigits()}:${local.minute.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun formatCardInfoDays(days: Int): String = when {
    days < 30 -> "$days days"
    days < 365 -> formatCardInfoDecimal(days / 30.0, " months")
    else -> formatCardInfoDecimal(days / 365.0, " years")
}

private fun formatCardInfoDecimal(value: Double, suffix: String = ""): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return "$rounded$suffix"
}
