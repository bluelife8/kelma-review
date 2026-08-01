package tech.kelma.app

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class AccountStudyDayPolicy(
    val version: Long = 0,
    @SerialName("timezone_id") val timezoneId: String = "UTC",
    @SerialName("day_start_hour") val dayStartHour: Int = 4,
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("last_client_id") val lastClientId: String = "",
) {
    // The policy is immutable, so its resolved zone is immutable too. Keeping the cache on the
    // policy avoids both repeated native tz-database parsing and shared mutable global state.
    // A copied or deserialized policy gets its own cache for its own timezoneId.
    private val resolvedTimeZone: TimeZone by lazy { TimeZone.of(timezoneId) }

    internal fun resolvedZone(): TimeZone {
        require(dayStartHour in 0..23) { "Study-day start hour must be between 0 and 23" }
        return resolvedTimeZone
    }

    fun validated(): AccountStudyDayPolicy {
        resolvedZone()
        return this
    }

    fun toCandidate(idempotencyKey: String = randomUuidString()): StudyDayPolicyCandidate =
        StudyDayPolicyCandidate(
            baseVersion = version,
            timezoneId = timezoneId,
            dayStartHour = dayStartHour,
            idempotencyKey = idempotencyKey,
        )

    companion object {
        fun systemDefault(): AccountStudyDayPolicy = AccountStudyDayPolicy(
            timezoneId = TimeZone.currentSystemDefault().id,
        )
    }
}

@Serializable
data class StudyDayPolicyCandidate(
    @SerialName("base_version") val baseVersion: Long,
    @SerialName("timezone_id") val timezoneId: String,
    @SerialName("day_start_hour") val dayStartHour: Int,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

internal fun studyDayAt(epochMillis: Long, policy: AccountStudyDayPolicy): Long {
    val zone = policy.resolvedZone()
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val date = if (local.time.hour < policy.dayStartHour) {
        local.date.minus(1, DateTimeUnit.DAY)
    } else {
        local.date
    }
    return date.toEpochDays()
}

/**
 * The half-open `[startMillis, endMillisExclusive)` epoch-millis range whose [studyDayAt] equals the
 * study day containing [nowMillis]. `epochMillis in window` is exactly `studyDayAt(epochMillis) ==
 * studyDayAt(nowMillis)`, letting hot loops replace per-review [studyDayAt] calls with an integer
 * comparison.
 */
internal class StudyDayWindow(val startMillis: Long, val endMillisExclusive: Long) {
    operator fun contains(epochMillis: Long): Boolean =
        epochMillis >= startMillis && epochMillis < endMillisExclusive
}

internal fun studyDayWindow(nowMillis: Long, policy: AccountStudyDayPolicy): StudyDayWindow {
    val zone = policy.resolvedZone()
    val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
    val studyDate = if (local.time.hour < policy.dayStartHour) {
        local.date.minus(1, DateTimeUnit.DAY)
    } else {
        local.date
    }
    val start = studyDate.atTime(policy.dayStartHour, 0).toInstant(zone).toEpochMilliseconds()
    val end = studyDate.plus(1, DateTimeUnit.DAY).atTime(policy.dayStartHour, 0)
        .toInstant(zone).toEpochMilliseconds()
    return StudyDayWindow(start, end)
}

internal fun nextStudyDayStart(epochMillis: Long, policy: AccountStudyDayPolicy): Long {
    val zone = policy.resolvedZone()
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val todayStart = local.date.atTime(policy.dayStartHour, 0)
    val nextStart = if (local < todayStart) todayStart else {
        local.date.plus(1, DateTimeUnit.DAY).atTime(policy.dayStartHour, 0)
    }
    return nextStart.toInstant(zone).toEpochMilliseconds()
}

/**
 * Aligns day-based reviews and interday learning steps to the account's study
 * rollover. FSRS still chooses the interval; this changes only its wall-clock
 * representation. Intraday learning retains its exact delay.
 */
internal fun LocalCardSchedule.alignedToStudyDay(policy: AccountStudyDayPolicy): LocalCardSchedule {
    val alignedDueAt = when {
        phase == ReviewPhase.Review && scheduledDays > 0 ->
            studyDayStartAfter(lastReviewAtMillis, scheduledDays, policy)
        phase != ReviewPhase.Review && dueAtMillis != Long.MAX_VALUE &&
            studyDayAt(dueAtMillis, policy) > studyDayAt(lastReviewAtMillis, policy) ->
            studyDayStartAt(dueAtMillis, policy)
        else -> dueAtMillis
    }
    return if (alignedDueAt == dueAtMillis) this else copy(dueAtMillis = alignedDueAt)
}

private fun studyDayStartAfter(
    epochMillis: Long,
    days: Int,
    policy: AccountStudyDayPolicy,
): Long {
    val zone = policy.resolvedZone()
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val studyDate = if (local.time.hour < policy.dayStartHour) {
        local.date.minus(1, DateTimeUnit.DAY)
    } else {
        local.date
    }
    return studyDate.plus(days, DateTimeUnit.DAY)
        .atTime(policy.dayStartHour, 0)
        .toInstant(zone)
        .toEpochMilliseconds()
}

private fun studyDayStartAt(epochMillis: Long, policy: AccountStudyDayPolicy): Long {
    val zone = policy.resolvedZone()
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val studyDate = if (local.time.hour < policy.dayStartHour) {
        local.date.minus(1, DateTimeUnit.DAY)
    } else {
        local.date
    }
    return studyDate.atTime(policy.dayStartHour, 0).toInstant(zone).toEpochMilliseconds()
}
