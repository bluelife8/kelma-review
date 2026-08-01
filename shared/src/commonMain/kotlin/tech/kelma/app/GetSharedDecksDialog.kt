package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val KelmaSharedDecksUrl = "https://kelma.tech/"
internal const val AnkiWebSharedDecksUrl = "https://ankiweb.net/shared/decks"

@Composable
internal fun GetSharedDecksDialog(
    onOpenUri: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("get-shared-dialog"),
        title = { Text("Get shared decks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedDeckSource(
                    title = "Kelma",
                    address = "kelma.tech",
                    testTag = "shared-decks-kelma",
                    onClick = {
                        onDismiss()
                        onOpenUri(KelmaSharedDecksUrl)
                    },
                )
                SharedDeckSource(
                    title = "AnkiWeb",
                    address = "ankiweb.net/shared/decks",
                    testTag = "shared-decks-ankiweb",
                    onClick = {
                        onDismiss()
                        onOpenUri(AnkiWebSharedDecksUrl)
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SharedDeckSource(
    title: String,
    address: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(testTag),
        color = if (isDesktopApp) KelmaDesktopColors.SurfaceHigh else KelmaColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(address, color = KelmaColors.TextSecondary, fontSize = 13.sp)
        }
    }
}
