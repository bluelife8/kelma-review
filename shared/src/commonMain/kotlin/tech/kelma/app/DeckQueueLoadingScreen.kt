package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeckQueueLoadingScreen(deckName: String, onBack: () -> Unit) {
    val background = if (isDesktopApp) KelmaDesktopColors.Background else KelmaColors.Background
    val textColor = if (isDesktopApp) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val accent = if (isDesktopApp) KelmaDesktopColors.Gold else KelmaColors.Gold
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(modifier = if (isDesktopApp) Modifier.safeContentPadding() else Modifier.statusBarsPadding()) {
            if (isDesktopApp) {
                DesktopTopToolbar(onDecks = onBack, onSync = {})
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = textColor)
                    }
                    Text(deckName, color = textColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                }
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accent)
                    Text(
                        "Preparing review…",
                        modifier = Modifier.padding(top = 14.dp),
                        color = textColor,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
