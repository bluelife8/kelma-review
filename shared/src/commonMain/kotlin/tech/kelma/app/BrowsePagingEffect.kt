package tech.kelma.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun BrowsePagingEffect(
    listState: LazyListState,
    loadedCount: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, loadedCount, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= (loadedCount - 20).coerceAtLeast(0)) currentLoadMore()
            }
    }
}
