package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sync/stats hot paths replace per-review `studyDayAt(...) == studyDay` checks with an integer
 * [StudyDayWindow]. These tests pin the invariant that makes that substitution safe:
 * `epochMillis in studyDayWindow(now) == (studyDayAt(epochMillis) == studyDayAt(now))`.
 */
class StudyDayWindowTest {

    @Test
    fun optimizedHelpersPreservePolicyValidation() {
        assertFailsWith<IllegalArgumentException> {
            studyDayAt(0L, AccountStudyDayPolicy(timezoneId = "UTC", dayStartHour = 24))
        }
        assertFailsWith<IllegalArgumentException> {
            studyDayAt(0L, AccountStudyDayPolicy(timezoneId = "Not/A_Timezone", dayStartHour = 4))
        }
    }

    @Test
    fun copiedPolicyResolvesItsOwnTimezone() {
        val instant = 1_700_000_000_000L
        val utc = AccountStudyDayPolicy(timezoneId = "UTC", dayStartHour = 4)
        val newYork = utc.copy(timezoneId = "America/New_York")

        // Resolve the source first; the copy must not inherit that cached zone.
        studyDayAt(instant, utc)
        assertEquals(
            studyDayAt(instant, AccountStudyDayPolicy(timezoneId = "America/New_York", dayStartHour = 4)),
            studyDayAt(instant, newYork),
        )
    }

    @Test
    fun windowBoundariesMatchStudyDayAtForUtc() {
        val policy = AccountStudyDayPolicy(timezoneId = "UTC", dayStartHour = 4)
        val now = 1_700_000_000_000L
        val studyDay = studyDayAt(now, policy)
        val window = studyDayWindow(now, policy)

        assertEquals(studyDay, studyDayAt(window.startMillis, policy))
        assertEquals(studyDay - 1, studyDayAt(window.startMillis - 1, policy))
        assertEquals(studyDay, studyDayAt(window.endMillisExclusive - 1, policy))
        assertEquals(studyDay + 1, studyDayAt(window.endMillisExclusive, policy))
        assertTrue(window.startMillis in window)
        assertTrue(window.endMillisExclusive - 1 in window)
        assertTrue(window.endMillisExclusive !in window)
        assertTrue(window.startMillis - 1 !in window)
    }

    @Test
    fun windowMatchesStudyDayAtAcrossSweepInEveryZone() {
        val cases = listOf(
            AccountStudyDayPolicy(timezoneId = "UTC", dayStartHour = 0),
            AccountStudyDayPolicy(timezoneId = "UTC", dayStartHour = 4),
            AccountStudyDayPolicy(timezoneId = "America/New_York", dayStartHour = 3),
            AccountStudyDayPolicy(timezoneId = "Asia/Kolkata", dayStartHour = 4),
            AccountStudyDayPolicy(timezoneId = "Pacific/Chatham", dayStartHour = 6),
        )
        // Anchor near a US spring-forward transition (2024-03-10) so a 23-hour day is exercised.
        val anchor = 1_710_000_000_000L
        for (policy in cases) {
            val now = anchor
            val studyDay = studyDayAt(now, policy)
            val window = studyDayWindow(now, policy)
            var probe = now - 3L * MillisPerDay
            val end = now + 3L * MillisPerDay
            val step = 37L * 60_000L // 37 minutes: crosses hour/DST boundaries without O(n) cost
            while (probe <= end) {
                val expected = studyDayAt(probe, policy) == studyDay
                assertEquals(
                    expected,
                    probe in window,
                    "zone=${policy.timezoneId} dayStart=${policy.dayStartHour} probe=$probe",
                )
                probe += step
            }
        }
    }
}
