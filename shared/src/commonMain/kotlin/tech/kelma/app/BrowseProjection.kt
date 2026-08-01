package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val BrowsePageSize = 50

internal data class BrowseProjectionView(
    val rows: List<BrowseCardRow> = emptyList(),
    val totalCount: Int = 0,
    val decks: List<Pair<String, Int>> = emptyList(),
    val tags: List<Pair<String, Int>> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val onLoadMore: () -> Unit = {},
)

internal val LocalBrowsePageLoader = compositionLocalOf<BrowsePageLoader?> { null }

@Composable
internal fun rememberBrowseProjection(
    collection: SyncedCollection,
    schedules: Map<Long, LocalCardSchedule>,
    dueDateOverrides: Map<Long, Long>,
    query: String,
    sorting: BrowseSorting,
    nowMillis: Long,
): BrowseProjectionView {
    val sharedLoader = LocalBrowsePageLoader.current
    val fallbackLoader = remember(
        ProjectionIdentity(collection),
        ProjectionIdentity(schedules),
        ProjectionIdentity(dueDateOverrides),
    ) {
        inMemoryBrowsePageLoader(collection, schedules, dueDateOverrides)
    }
    val loader = sharedLoader ?: fallbackLoader
    val sourceKey = ProjectionIdentity(collection)
    var rows by remember(sourceKey) {
        mutableStateOf<List<BrowseCardRow>>(emptyList(), referentialEqualityPolicy())
    }
    var totalCount by remember(sourceKey) { mutableStateOf(0) }
    var decks by remember(sourceKey) { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var tags by remember(sourceKey) { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loading by remember(sourceKey) { mutableStateOf(true) }
    var loadingMore by remember(sourceKey) { mutableStateOf(false) }
    var nextOffset by remember(sourceKey) { mutableStateOf<Int?>(null) }
    var loadedQuery by remember(sourceKey) { mutableStateOf<String?>(null) }
    val timeKey = if (queryHasTerm(query, "is:due")) nowMillis / 30_000L else 0L
    val queryId = remember(
        ProjectionIdentity(loader),
        ProjectionIdentity(schedules),
        ProjectionIdentity(dueDateOverrides),
        query,
        sorting,
        timeKey,
    ) { randomUuidString() }

    LaunchedEffect(
        ProjectionIdentity(loader),
        ProjectionIdentity(schedules),
        ProjectionIdentity(dueDateOverrides),
        query,
        sorting,
        timeKey,
    ) {
        nextOffset = null
        loadingMore = false
        loading = true
        if (loadedQuery != null && loadedQuery != query) delay(75)
        val page = withContext(Dispatchers.Default) {
            loader.load(
                BrowsePageRequest(
                    query,
                    sorting,
                    nowMillis,
                    offset = 0,
                    limit = BrowsePageSize,
                    queryId = queryId,
                ),
            )
        }
        rows = page.rows
        totalCount = page.totalCount
        decks = page.decks
        tags = page.tags
        loadedQuery = query
        loading = false
    }

    LaunchedEffect(
        ProjectionIdentity(loader),
        ProjectionIdentity(schedules),
        ProjectionIdentity(dueDateOverrides),
        query,
        sorting,
        timeKey,
        nextOffset,
    ) {
        val offset = nextOffset ?: return@LaunchedEffect
        if (offset <= 0) return@LaunchedEffect
        loadingMore = true
        val page = withContext(Dispatchers.Default) {
            loader.load(BrowsePageRequest(query, sorting, nowMillis, offset, BrowsePageSize, queryId))
        }
        if (nextOffset == offset) {
            rows = rows + page.rows
            totalCount = page.totalCount
            nextOffset = null
        }
        loadingMore = false
    }

    return BrowseProjectionView(
        rows = rows,
        totalCount = totalCount,
        decks = decks,
        tags = tags,
        loading = loading,
        loadingMore = loadingMore,
        hasMore = rows.size < totalCount,
        onLoadMore = {
            if (!loading && !loadingMore && nextOffset == null && rows.size < totalCount) {
                nextOffset = rows.size
            }
        },
    )
}

private fun inMemoryBrowsePageLoader(
    collection: SyncedCollection,
    schedules: Map<Long, LocalCardSchedule>,
    dueDateOverrides: Map<Long, Long>,
): BrowsePageLoader = BrowsePageLoader { request ->
    val allRows = collection.browseRows(schedules, dueDateOverrides)
    val terms = parseBrowseQuery(request.query)
    val filtered = allRows.filter { it.matches(terms, request.nowMillis) }.sortedForBrowse(request.sorting)
    val decks = allRows.groupingBy(BrowseCardRow::deck).eachCount().entries
        .sortedBy { it.key.lowercase() }
        .map { it.toPair() }
    val tags = allRows.flatMap(BrowseCardRow::tags).groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }
        .take(12)
        .map { it.toPair() }
    val pageStart = request.offset.coerceIn(0, filtered.size)
    val pageEnd = (pageStart.toLong() + request.limit).coerceAtMost(filtered.size.toLong()).toInt()
    BrowsePage(
        rows = filtered.subList(pageStart, pageEnd),
        totalCount = filtered.size,
        decks = decks,
        tags = tags,
    )
}
