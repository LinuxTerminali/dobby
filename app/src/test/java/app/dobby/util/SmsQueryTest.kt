package app.dobby.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsQueryTest {
    @Test fun expandsBillVocabulary() {
        val t = SmsQuery.expand("bills due this week")
        assertTrue(t.toString(), t.containsAll(listOf("due", "payable", "amt due", "bill")))
    }
    @Test fun keepsSenderNamesAndDropsStopWords() {
        assertEquals(listOf("bescom"), SmsQuery.expand("Find my SMS from BESCOM"))
        assertEquals(listOf("avl bal", "available balance", "balance", "bal"), SmsQuery.expand("the bank balance").filter { it != "bank" })
    }
    @Test fun rawQueryWhenOnlyStopWords() {
        assertEquals(listOf("my"), SmsQuery.expand("my"))
    }
}
