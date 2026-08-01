package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DueDateTest {
    @Test
    fun isoDateParsingValidatesCalendarDatesAndRoundTrips() {
        val leapDay = parseDueDateMillis("2028-02-29")

        assertEquals("2028-02-29", leapDay?.let(::formatDueDate))
        assertNull(parseDueDateMillis("2027-02-29"))
        assertNull(parseDueDateMillis("2028-13-01"))
        assertNull(parseDueDateMillis("2028-04-31"))
        assertNull(parseDueDateMillis("02/29/2028"))
    }

    @Test
    fun futureDueDateMakesANewCardNotDueInBrowse() {
        val now = requireNotNull(parseDueDateMillis("2028-02-28"))
        val future = requireNotNull(parseDueDateMillis("2028-02-29"))
        val row = BrowseCardRow(
            cardId = 1L,
            noteGuid = "note",
            question = "front",
            answer = "back",
            deck = "Deck",
            notetype = "Basic",
            tags = emptyList(),
            state = BrowseCardState.New,
            dueMillis = future,
            isLocal = true,
        )

        assertFalse(row.matches(parseBrowseQuery("is:due"), now))
        assertTrue(row.matches(parseBrowseQuery("is:due"), future))
    }
}
