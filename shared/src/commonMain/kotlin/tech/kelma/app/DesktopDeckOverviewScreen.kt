package tech.kelma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
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
fun DesktopDeckOverviewScreen(
    deck: DeckSummary,
    syncing: Boolean,
    onDecks: () -> Unit,
    onSync: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit = {},
    onOptions: () -> Unit = {},
    onStudy: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(modifier = Modifier.safeContentPadding()) {
            DesktopTopToolbar(
                onDecks = onDecks,
                onSync = onSync,
                onAdd = onAdd,
                onBrowse = onBrowse,
                onOptions = onOptions,
                syncing = syncing,
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.padding(top = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = deck.name,
                        color = KelmaDesktopColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(34.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(72.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OverviewCount("New:", deck.newCount, KelmaDesktopColors.New)
                            OverviewCount("Learning:", deck.learningCount, KelmaDesktopColors.Learn)
                            OverviewCount("To Review:", deck.dueCount, KelmaDesktopColors.Due)
                        }
                        DesktopGoldButton("Study Now", width = 210.dp, onClick = onStudy)
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DesktopUtilityButton("Options")
                    DesktopUtilityButton("Custom Study")
                    DesktopUtilityButton("Description")
                }
            }
        }
    }
}

@Composable
private fun OverviewCount(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            color = KelmaDesktopColors.TextPrimary,
            fontSize = 15.sp,
        )
        Text(
            text = value.toString(),
            modifier = Modifier.width(34.dp),
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
        )
    }
}
