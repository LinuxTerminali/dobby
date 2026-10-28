package app.dobby.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTest {
    @Test fun intervalReminderStepsFromBase() {
        val base = 1_000_000L
        val r = Reminder("x", "poll", "", 0, 0, emptyList(), base, "check", true, everyMinutes = 15)
        assertEquals(base, r.nextTrigger(base - 1))
        assertEquals(base + 15 * 60_000L, r.nextTrigger(base))
        assertEquals(base + 30 * 60_000L, r.nextTrigger(base + 16 * 60_000L))
        assertTrue(r.recurring); assertEquals("every 15 min", r.describe())
    }

    @Test fun oneOffPastIsNull() {
        val r = Reminder("y", "once", "", 9, 0, emptyList(), 5_000L)
        assertNull(r.nextTrigger(6_000L)); assertEquals(5_000L, r.nextTrigger(1_000L))
    }

    @Test fun jsonRoundTrip() {
        val r = Reminder("z", "t", "m", 8, 30, listOf(2, 3), 0, "p", false, 20)
        assertEquals(r, Reminder.fromJson(r.toJson()))
    }
}
