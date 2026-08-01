package tech.kelma.app

data class DailyStudyStats(
    val epochDay: Long,
    val reviews: Int,
    val studiedMillis: Long,
)

data class StudyStats(
    val totalReviews: Int = 0,
    val reviewsToday: Int = 0,
    val studiedMillisToday: Long = 0,
    val totalStudiedMillis: Long = 0,
    val recalledReviews: Int = 0,
    val forgottenReviews: Int = 0,
    val currentStreakDays: Int = 0,
    val cards: Int = 0,
    val newCards: Int = 0,
    val learningCards: Int = 0,
    val reviewCards: Int = 0,
    val dueCards: Int = 0,
    val matureCards: Int = 0,
    val daily: List<DailyStudyStats> = emptyList(),
) {
    val recallRate: Double?
        get() = (recalledReviews + forgottenReviews).takeIf { it > 0 }
            ?.let { recalledReviews.toDouble() / it }
}

internal data class StudyStatsReview(
    val reviewId: Long,
    val rating: Int,
    val durationMillis: Long,
    val epochDay: Long,
)

internal fun calculateStudyStats(
    reviews: List<StudyStatsReview>,
    cards: Collection<SyncCard>,
    schedules: Map<Long, LocalCardSchedule>,
    nowMillis: Long,
    chartDays: Int = 30,
    dueDateOverrides: Map<Long, Long> = emptyMap(),
    studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
): StudyStats {
    val today = studyDayAt(nowMillis, studyDayPolicy)
    val byDay = reviews.groupBy(StudyStatsReview::epochDay)
    val daily = ((today - chartDays + 1)..today).map { day ->
        val events = byDay[day].orEmpty()
        DailyStudyStats(day, events.size, events.sumOf(StudyStatsReview::durationMillis))
    }
    var streak = 0
    var day = today
    while (byDay[day].orEmpty().isNotEmpty()) {
        streak++
        day--
    }
    val recalled = reviews.count { it.rating in 2..4 }
    val forgotten = reviews.count { it.rating == 1 }
    val activeCards = cards.filter { it.studyState == CardStudyState.Active }
    val nowSchedules = activeCards.mapNotNull { schedules[it.cardId] }
    return StudyStats(
        totalReviews = reviews.size,
        reviewsToday = byDay[today].orEmpty().size,
        studiedMillisToday = byDay[today].orEmpty().sumOf(StudyStatsReview::durationMillis),
        totalStudiedMillis = reviews.sumOf(StudyStatsReview::durationMillis),
        recalledReviews = recalled,
        forgottenReviews = forgotten,
        currentStreakDays = streak,
        cards = cards.size,
        newCards = activeCards.count { it.cardId !in schedules },
        learningCards = nowSchedules.count { it.phase != ReviewPhase.Review },
        reviewCards = nowSchedules.count { it.phase == ReviewPhase.Review },
        dueCards = activeCards.count { card ->
            schedules[card.cardId]?.let { schedule ->
                (dueDateOverrides[card.cardId] ?: schedule.dueAtMillis) <= nowMillis
            } ?: false
        },
        matureCards = nowSchedules.count { it.phase == ReviewPhase.Review && it.scheduledDays >= 21 },
        daily = daily,
    )
}

internal fun formatStudyDuration(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0L) "${minutes}m" else "${hours}h ${minutes}m"
}
