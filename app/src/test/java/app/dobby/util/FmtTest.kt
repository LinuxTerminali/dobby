package app.dobby.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class FmtTest {
    init { Fmt.zone = TimeZone.getTimeZone("Asia/Kolkata") }

    @Test fun inrUsesIndianGrouping() {
        assertEquals("₹1,32,450.10", Fmt.inr(132450.10))
        assertEquals("₹640", Fmt.inr(640.0))
        assertEquals("₹2,140", Fmt.inr(2140.0))
        assertEquals("₹95,000", Fmt.inr(95000.0))
        assertEquals("₹12,34,56,789.05", Fmt.inr(123456789.05))
        assertEquals("-₹5", Fmt.inr(-5.0))
    }

    @Test fun durations() {
        assertEquals("45 s", Fmt.duration(45)); assertEquals("10 min 0 s", Fmt.duration(600)); assertEquals("1 h 5 min", Fmt.duration(3900))
    }

    @Test fun isoDateReadable() {
        assertEquals("Wed 28 Oct 2026", Fmt.isoDate("2026-10-28"))
        assertEquals("28-10-2026", Fmt.isoDate("28-10-2026"))
    }

    @Test fun dateTimeHasWeekdayAndAmPm() {
        // 2026-10-26 21:01 IST
        val s = Fmt.dateTime(1793028660000L)
        assertEquals("Mon 26 Oct, 9:01 PM", s)
    }

    @Test fun relativeMinutes() {
        assertEquals(120, Fmt.relativeMinutes("2 hours")); assertEquals(30, Fmt.relativeMinutes("in 30 min"))
        assertEquals(75, Fmt.relativeMinutes("1 hr 15 minutes")); assertEquals(1440, Fmt.relativeMinutes("tomorrow"))
        assertNull(Fmt.relativeMinutes("18:30")); assertNull(Fmt.relativeMinutes("2026-10-28 09:00"))
    }
}
