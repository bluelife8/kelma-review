package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedReviewCard(
    val card: ReviewCard,
    val documents: PreparedStudyCard?,
)

@Composable
internal fun rememberPreparedReviewCard(
    session: ReviewSession,
    desktopLayout: Boolean,
    loadMedia: (String) -> ByteArray?,
): PreparedReviewCard {
    val sourceCard = requireNotNull(session.currentCard)
    val nextCard = session.nextQueuedCard
    val preparedCards = remember { mutableStateMapOf<Long, CachedPreparedReviewCard>() }
    LaunchedEffect(sourceCard, nextCard) {
        val window = listOfNotNull(sourceCard, nextCard).distinctBy(ReviewCard::id)
        val retainedIds = window.mapTo(mutableSetOf(), ReviewCard::id)
        preparedCards.keys.toList().filterNot(retainedIds::contains).forEach(preparedCards::remove)
        window.forEach { queuedCard ->
            val cached = preparedCards[queuedCard.id]
            if (cached?.source == queuedCard) return@forEach
            val prepared = withContext(Dispatchers.Default) {
                val hydrated = if (queuedCard.hasUnloadedMedia()) {
                    queuedCard.hydrateMedia(loadMedia)
                } else {
                    queuedCard
                }
                CachedPreparedReviewCard(
                    source = queuedCard,
                    hydrated = hydrated,
                    documents = prepareStudyCard(hydrated, desktopLayout),
                )
            }
            preparedCards[queuedCard.id] = prepared
        }
    }
    val cached = preparedCards[sourceCard.id]?.takeIf { it.source == sourceCard }
    return PreparedReviewCard(
        card = cached?.hydrated ?: sourceCard,
        documents = cached?.documents,
    )
}

private data class CachedPreparedReviewCard(
    val source: ReviewCard,
    val hydrated: ReviewCard,
    val documents: PreparedStudyCard,
)
