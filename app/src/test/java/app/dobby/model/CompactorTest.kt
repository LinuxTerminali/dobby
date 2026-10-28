package app.dobby.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactorTest {
    @Test fun budgetRequiresTurnsAndSize() {
        assertFalse(Compactor.shouldCompact(100_000, 2))
        assertFalse(Compactor.shouldCompact(1_000, 20))
        assertTrue(Compactor.shouldCompact(Compactor.BUDGET_CHARS + 1L, Compactor.MIN_TURNS))
    }

    @Test fun splitKeepsRecentPairs() {
        val turns = (1..10).map { Compactor.Turn(if (it % 2 == 1) "user" else "model", "t$it") }
        val (older, keep) = Compactor.split(turns)
        assertEquals(6, older.size); assertEquals(4, keep.size); assertEquals("t7", keep.first().text)
    }

    @Test fun parsesSummaryAndFacts() {
        val out = """SUMMARY: User asked about calls with Mom and a BESCOM bill; the bill is 2,140 due 28 Oct. Open: nothing.
FACTS: [{"text":"Office map pin is wrong; use the north gate","tags":"office,cab"},{"text":"x","tags":""}]"""
        val r = Compactor.parse(out)
        assertTrue(r.summary.startsWith("User asked about calls"))
        assertEquals(1, r.facts.size)   // "x" too short to be a fact
        assertEquals("office,cab", r.facts[0].second)
    }

    @Test fun parseToleratesMissingFacts() {
        val r = Compactor.parse("SUMMARY: just chatting.")
        assertEquals("just chatting.", r.summary); assertTrue(r.facts.isEmpty())
        val r2 = Compactor.parse("garbage without labels")
        assertEquals("garbage without labels", r2.summary)
    }

    @Test fun promptIncludesTranscriptAndPreviousSummary() {
        val p = Compactor.prompt(listOf(Compactor.Turn("user", "hi"), Compactor.Turn("model", "hello")), "earlier stuff")
        assertTrue(p.contains("User: hi") && p.contains("Dobby: hello") && p.contains("Earlier summary: earlier stuff"))
        assertTrue(p.contains("SUMMARY:") && p.contains("FACTS:"))
    }
}
