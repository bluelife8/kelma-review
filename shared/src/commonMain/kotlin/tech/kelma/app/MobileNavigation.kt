package tech.kelma.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class MobileCollectionTab { Decks, Browse, Add, Options, Sync }

@Composable
internal fun MobileBottomNavigation(
    selected: MobileCollectionTab?,
    onDecks: () -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit,
    onSyncLog: () -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        containerColor = KelmaColors.BackgroundAlt,
        contentColor = KelmaColors.TextSecondary,
        tonalElevation = 0.dp,
    ) {
        MobileCollectionTab.entries.forEach { tab ->
            val item = when (tab) {
                MobileCollectionTab.Decks -> Triple("Decks", Icons.Rounded.Home, onDecks)
                MobileCollectionTab.Browse -> Triple("Browse", Icons.Rounded.Search, onBrowse)
                MobileCollectionTab.Add -> Triple("Add", Icons.Rounded.AddCircle, onAdd)
                MobileCollectionTab.Options -> Triple("Options", Icons.Rounded.Tune, onOptions)
                MobileCollectionTab.Sync -> Triple("Sync", Icons.Rounded.Sync, onSyncLog)
            }
            MobileNavigationItem(item.first, item.second, selected == tab, item.third)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MobileNavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 11.sp) },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = KelmaColors.Background,
            selectedTextColor = KelmaColors.GoldSoft,
            indicatorColor = KelmaColors.Gold,
            unselectedIconColor = KelmaColors.TextMuted,
            unselectedTextColor = KelmaColors.TextMuted,
        ),
    )
}
