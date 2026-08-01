package tech.kelma.app

import tech.kelma.db.KelmaQueries

internal fun loadStudyStatsReviews(
    queries: KelmaQueries,
    studyDayPolicy: AccountStudyDayPolicy,
): List<StudyStatsReview> {
    val confirmed = queries.selectReviews {
            reviewId, _, _, _, _, ease, _, _, _, takenMillis, _, _, _ ->
        StudyStatsReview(
            reviewId = reviewId,
            rating = ease.toInt(),
            durationMillis = takenMillis.coerceAtLeast(0),
            epochDay = studyDayAt(reviewId, studyDayPolicy),
        )
    }.executeAsList().filter { it.rating in 1..4 }
    val confirmedIds = confirmed.mapTo(mutableSetOf(), StudyStatsReview::reviewId)
    val pending = queries.selectAllLocalReviewEvents {
            _, _, _, _, _, rating, reviewedAt, _, duration, _, _, _, reviewId, _, _ ->
        StudyStatsReview(
            reviewId = reviewId,
            rating = Rating.valueOf(rating).ordinal + 1,
            durationMillis = duration.coerceAtLeast(0),
            epochDay = studyDayAt(reviewedAt, studyDayPolicy),
        )
    }.executeAsList().filter { it.rating in 1..4 && it.reviewId !in confirmedIds }
    return (confirmed + pending).sortedWith(
        compareBy<StudyStatsReview>(StudyStatsReview::reviewId).thenBy(StudyStatsReview::rating),
    )
}
