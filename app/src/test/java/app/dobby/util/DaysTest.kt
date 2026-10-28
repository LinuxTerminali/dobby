package app.dobby.util

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class DaysTest {
    @Test fun acceptsNumbersNamesAndWords() {
        assertEquals(listOf(2, 3, 4, 5, 6), Days.parse("weekdays"))
        assertEquals(listOf(2, 3, 4, 5, 6), Days.parse(JSONArray(listOf("mon", "tue", "wed", "thu", "fri"))))
        assertEquals(listOf(2, 4), Days.parse("Monday, Wednesday"))
        assertEquals(listOf(1, 7), Days.parse("weekends"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), Days.parse("daily"))
        assertEquals(listOf(2, 3, 4, 5, 6), Days.parse(JSONArray(listOf(2, 3, 4, 5, 6))))
        assertEquals(emptyList<Int>(), Days.parse(null))
        assertEquals(emptyList<Int>(), Days.parse(JSONArray(listOf(0, 9))))
    }
}
