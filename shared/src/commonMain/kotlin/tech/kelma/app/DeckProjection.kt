package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class DeckProjectionState(
    val decks: List<DeckSummary>,
    val loading: Boolean,
)

private data class CachedDeckProjection(
    val source: SyncedCollection? = null,
    val decks: List<DeckSummary> = emptyList(),
)

@Composable
internal fun rememberBaseDeckProjection(
    collection: SyncedCollection,
    localContent: LocalContentSnapshot,
    localReviews: LocalReviewSnapshot,
    nowMillis: Long,
    pendingSyncByDeck: Map<String, PendingDeckChanges>,
    selectedDeckId: String?,
    foreground: Boolean,
): DeckProjectionState {
    val projector = remember { DeckCountProjectionCache() }
    var cached by remember {
        mutableStateOf(CachedDeckProjection(), referentialEqualityPolicy())
    }
    val sourceMatches = cached.source === collection
    val visible = if (sourceMatches) cached.decks else emptyList()

    LaunchedEffect(
        foreground,
        selectedDeckId,
        ProjectionIdentity(collection),
        ProjectionIdentity(localContent),
        ProjectionIdentity(localReviews),
        nowMillis,
        ProjectionIdentity(pendingSyncByDeck),
    ) {
        if (!foreground) delay(150)
        val projected = withContext(Dispatchers.Default) {
            projector.project(
                collection = collection,
                localContent = localContent,
                localReviews = localReviews,
                nowMillis = nowMillis,
                pendingSyncByDeck = pendingSyncByDeck,
                selectedDeckId = selectedDeckId,
            )
        }
        cached = CachedDeckProjection(collection, projected)
    }
    return DeckProjectionState(
        decks = visible,
        loading = foreground && selectedDeckId == null && !sourceMatches,
    )
}
