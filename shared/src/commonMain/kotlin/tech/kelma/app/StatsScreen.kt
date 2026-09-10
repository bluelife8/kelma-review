package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun StatsScreen(
    stats: StudyStats,
    syncing: Boolean,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
    deckNames: List<String> = emptyList(),
    loadDeckStats: suspend (String) -> StudyStats = { stats },
) {
    val pickerOptions = remember(deckNames) { deckPickerOptions(deckNames) }
    val pickerDeckNames = remember(pickerOptions) { pickerOptions.map(DeckPickerOption::name) }
    var selectedDeck by remember { mutableStateOf<String?>(null) }
    var displayedStats by remember(stats) { mutableStateOf(stats) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pickerDeckNames) {
        if (selectedDeck !in pickerDeckNames) selectedDeck = null
    }
    LaunchedEffect(stats, selectedDeck) {
        val deckName = selectedDeck
        if (deckName == null) {
            displayedStats = stats
            loading = false
            loadError = null
        } else {
            loading = true
            loadError = null
            try {
                displayedStats = withContext(Dispatchers.Default) { loadDeckStats(deckName) }
                loading = false
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                loadError = exception.message ?: "Could not load deck statistics"
                loading = false
            }
        }
    }

    val selectDeck: (String?) -> Unit = { selectedDeck = it }
    if (isDesktopApp) {
        DesktopStatsScreen(
            stats = displayedStats,
            syncing = syncing,
            deckOptions = pickerOptions,
            selectedDeck = selectedDeck,
            loading = loading,
            loadError = loadError,
            onSelectDeck = selectDeck,
            onDecks = onDecks,
            onAdd = onAdd,
            onBrowse = onBrowse,
            onOptions = onOptions,
            onSync = onSync,
        )
    } else {
        MobileStatsScreen(
            stats = displayedStats,
            deckOptions = pickerOptions,
            selectedDeck = selectedDeck,
            loading = loading,
            loadError = loadError,
            onSelectDeck = selectDeck,
            onDecks = onDecks,
            onBrowse = onBrowse,
            onAdd = onAdd,
            onOptions = onOptions,
            onSync = onSync,
        )
    }
}

@Composable
private fun DesktopStatsScreen(
    stats: StudyStats,
    syncing: Boolean,
    deckOptions: List<DeckPickerOption>,
    selectedDeck: String?,
    loading: Boolean,
    loadError: String?,
    onSelectDeck: (String?) -> Unit,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(Modifier.safeContentPadding()) {
            DesktopTopToolbar(onDecks, onSync, onAdd, onBrowse, onOptions, syncing, "Stats")
            StatsContent(
                stats = stats,
                desktop = true,
                deckOptions = deckOptions,
                selectedDeck = selectedDeck,
                loading = loading,
                loadError = loadError,
                onSelectDeck = onSelectDeck,
            )
        }
    }
}

@Composable
private fun MobileStatsScreen(
    stats: StudyStats,
    deckOptions: List<DeckPickerOption>,
    selectedDeck: String?,
    loading: Boolean,
    loadError: String?,
    onSelectDeck: (String?) -> Unit,
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
        StatsContent(
            stats = stats,
            desktop = false,
            modifier = Modifier.padding(padding),
            deckOptions = deckOptions,
            selectedDeck = selectedDeck,
            loading = loading,
            loadError = loadError,
            onSelectDeck = onSelectDeck,
        )
    }
}

@Composable
private fun StatsContent(
    stats: StudyStats,
    desktop: Boolean,
    modifier: Modifier = Modifier,
    deckOptions: List<DeckPickerOption>,
    selectedDeck: String?,
    loading: Boolean,
    loadError: String?,
    onSelectDeck: (String?) -> Unit,
) {
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
        if (desktop) {
            DesktopStatsDeckPicker(
                deckNames = deckOptions.map(DeckPickerOption::name),
                selectedDeck = selectedDeck,
                onSelectDeck = onSelectDeck,
            )
        } else {
            MobileDeckFilterPicker(
                options = deckOptions,
                selectedName = selectedDeck,
                testTagPrefix = "stats",
                onSelectName = onSelectDeck,
            )
        }
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("stats-loading"),
                color = if (desktop) KelmaDesktopColors.Accent else KelmaColors.Gold,
            )
        }
        loadError?.let { Text(it, color = KelmaColors.Bad, fontSize = 12.sp) }
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
private fun DesktopStatsDeckPicker(
    deckNames: List<String>,
    selectedDeck: String?,
    onSelectDeck: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val surface = KelmaDesktopColors.Surface
    val border = KelmaDesktopColors.Border
    val primary = KelmaDesktopColors.TextPrimary
    val secondary = KelmaDesktopColors.TextSecondary
    Column(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("VIEW", color = secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.testTag("stats-deck-picker"),
                color = surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedDeck ?: "All decks",
                        modifier = Modifier.weight(1f),
                        color = primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose statistics deck", tint = secondary)
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
                deckNames.forEach { name ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width((deckHierarchyNames(name).lastIndex * 12).dp))
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        modifier = Modifier.testTag("stats-deck-$name"),
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
