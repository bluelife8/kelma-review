package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StatsScreen(
    stats: StudyStats,
    syncing: Boolean,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
) {
    if (isDesktopApp) {
        DesktopStatsScreen(stats, syncing, onDecks, onAdd, onBrowse, onOptions, onSync)
    } else {
        MobileStatsScreen(stats, onDecks, onBrowse, onAdd, onOptions, onSync)
    }
}

@Composable
private fun DesktopStatsScreen(
    stats: StudyStats,
    syncing: Boolean,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(Modifier.safeContentPadding()) {
            DesktopTopToolbar(onDecks, onSync, onAdd, onBrowse, onOptions, syncing, "Stats")
            StatsContent(stats, desktop = true)
        }
    }
}

@Composable
private fun MobileStatsScreen(
    stats: StudyStats,
    onDecks: () -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
) {
    Scaffold(
        containerColor = KelmaColors.Background,
        topBar = {
            Surface(Modifier.statusBarsPadding(), color = KelmaColors.Background) {
                Text(
                    "Stats",
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    color = KelmaColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        },
        bottomBar = {
            MobileBottomNavigation(null, onDecks, onBrowse, onAdd, onOptions, onSync)
        },
    ) { padding ->
        StatsContent(stats, desktop = false, Modifier.padding(padding))
    }
}

@Composable
private fun StatsContent(stats: StudyStats, desktop: Boolean, modifier: Modifier = Modifier) {
    val primary = if (desktop) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val secondary = if (desktop) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
    val surface = if (desktop) KelmaDesktopColors.Surface else KelmaColors.Surface
    val border = if (desktop) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = if (desktop) 48.dp else 20.dp, vertical = 20.dp)
            .widthIn(max = 980.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Study history", color = primary, fontSize = if (desktop) 28.sp else 30.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Today", stats.reviewsToday.toString(), formatStudyDuration(stats.studiedMillisToday), surface, border, primary, secondary, Modifier.weight(1f))
            StatTile("Recall", stats.recallRate?.let { "${(it * 100).toInt()}%" } ?: "—", "${stats.totalReviews} reviews", surface, border, primary, secondary, Modifier.weight(1f))
            StatTile("Streak", "${stats.currentStreakDays}d", formatStudyDuration(stats.totalStudiedMillis), surface, border, primary, secondary, Modifier.weight(1f))
        }
        Surface(
            Modifier.fillMaxWidth(), color = surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, border),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Last 30 days", color = primary, fontWeight = FontWeight.Bold)
                DailyReviewChart(stats.daily, if (desktop) KelmaDesktopColors.Accent else KelmaColors.Good)
                Text("${stats.daily.sumOf(DailyStudyStats::reviews)} reviews", color = secondary, fontSize = 12.sp)
            }
        }
        Surface(
            Modifier.fillMaxWidth(), color = surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, border),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cards", color = primary, fontWeight = FontWeight.Bold)
                StatsLine("Total", stats.cards, primary, secondary)
                StatsLine("New", stats.newCards, primary, secondary)
                StatsLine("Learning", stats.learningCards, primary, secondary)
                StatsLine("Review", stats.reviewCards, primary, secondary)
                StatsLine("Due now", stats.dueCards, primary, secondary)
                StatsLine("Mature (21+ days)", stats.matureCards, primary, secondary)
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    detail: String,
    surface: Color,
    border: Color,
    primary: Color,
    secondary: Color,
    modifier: Modifier,
) {
    Surface(modifier, color = surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = secondary, fontSize = 11.sp)
            Text(value, color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = secondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DailyReviewChart(days: List<DailyStudyStats>, color: Color) {
    val maximum = days.maxOfOrNull(DailyStudyStats::reviews)?.coerceAtLeast(1) ?: 1
    Canvas(Modifier.fillMaxWidth().height(120.dp).testTag("stats-daily-chart")) {
        val slot = size.width / days.size.coerceAtLeast(1)
        days.forEachIndexed { index, day ->
            val height = size.height * day.reviews / maximum
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(slot * (index + 0.5f), size.height),
                end = androidx.compose.ui.geometry.Offset(slot * (index + 0.5f), size.height - height),
                strokeWidth = (slot * 0.62f).coerceAtLeast(2f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun StatsLine(label: String, value: Int, primary: Color, secondary: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = secondary, fontSize = 13.sp)
        Text(value.toString(), color = primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
