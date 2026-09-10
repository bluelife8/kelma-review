package tech.kelma.app

import tech.kelma.db.KelmaQueries

internal fun loadStudyStatsReviews(
    queries: KelmaQueries,
    studyDayPolicy: AccountStudyDayPolicy,
    deckNameOverrides: Map<String, String?> = emptyMap(),
): List<StudyStatsReview> {
    val confirmed = queries.selectReviews {
            reviewId, _, _, _, deckName, ease, _, _, _, takenMillis, _, _, _ ->
        StudyStatsReview(
            reviewId = reviewId,
            rating = ease.toInt(),
            durationMillis = takenMillis.coerceAtLeast(0),
            epochDay = studyDayAt(reviewId, studyDayPolicy),
            deckName = deckName,
        )
    }.executeAsList()
        .filter { it.rating in 1..4 }
        .withDeckOverrides(deckNameOverrides)
    val confirmedIds = confirmed.mapTo(mutableSetOf(), StudyStatsReview::reviewId)
    val pending = queries.selectAllLocalReviewEvents {
            _, _, _, _, deckName, rating, reviewedAt, _, duration, _, _, _, reviewId, _, _ ->
        StudyStatsReview(
            reviewId = reviewId,
            rating = Rating.valueOf(rating).ordinal + 1,
            durationMillis = duration.coerceAtLeast(0),
            epochDay = studyDayAt(reviewedAt, studyDayPolicy),
            deckName = deckName,
        )
    }.executeAsList()
        .filter { it.rating in 1..4 && it.reviewId !in confirmedIds }
        .withDeckOverrides(deckNameOverrides)
    return (confirmed + pending).sortedWith(
        compareBy<StudyStatsReview>(StudyStatsReview::reviewId).thenBy(StudyStatsReview::rating),
    )
}

private fun List<StudyStatsReview>.withDeckOverrides(
    overrides: Map<String, String?>,
): List<StudyStatsReview> {
    if (overrides.isEmpty()) return this
    return map { review ->
        review.copy(deckName = review.deckName.remapDownloadedDeckName(overrides) ?: review.deckName)
    }
}
