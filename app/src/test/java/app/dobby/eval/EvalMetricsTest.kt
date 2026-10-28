package app.dobby.eval

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalMetricsTest {

    @Test fun normalisesIndianGrouping() {
        assertEquals("132450.1", EvalMetrics.normalise("1,32,450.10"))
        assertEquals("2140", EvalMetrics.normalise("2,140.00"))
        assertEquals("1", EvalMetrics.normalise("01"))
    }

    @Test fun extractsNumbers() {
        assertEquals(setOf("2140", "28", "10", "2026"), EvalMetrics.numbers("bill Rs.2,140.00 due 28-10-2026"))
    }

    @Test fun groundedAcceptsNumbersFromSources() {
        val (ok, bad) = EvalMetrics.grounded("You paid ₹995 across 2 orders", listOf("""{"amount":"995.00"}"""))
        assertTrue(bad.toString(), ok)
    }

    @Test fun groundedRejectsInventedNumbers() {
        val (ok, bad) = EvalMetrics.grounded("Your balance is 1,16,810.10 and rent is 42000", listOf("Avl Bal Rs.1,16,810.10"))
        assertFalse(ok); assertEquals(setOf("42000"), bad)
    }

    @Test fun argsMatchSubsetAndTilde() {
        val actual = JSONObject("""{"who":"Shubham Chhabra Moe","days":30}""")
        assertTrue(EvalMetrics.argsMatch(JSONObject("""{"who":"~shubham"}"""), actual))
        assertFalse(EvalMetrics.argsMatch(JSONObject("""{"who":"priya"}"""), actual))
        assertTrue(EvalMetrics.argsMatch(JSONObject("""{"days":30}"""), actual))
        assertTrue(EvalMetrics.argsMatch(null, actual))
        assertTrue(EvalMetrics.argsMatch(JSONObject("""{"days":[2,3]}"""), JSONObject("""{"days":[2,3]}""")))
    }

    @Test fun scoresPassingCase() {
        val case = JSONObject("""{"id":"x","group":"calls","prompt":"call Shubham?","expect_tool":"calls.with","expect_args":{"who":"~shubham"},"reply_contains":"26"}""")
        val r = EvalMetrics.score(case, listOf("calls.with" to JSONObject("""{"who":"Shubham"}""")), setOf("calls.with"),
            "You called Shubham on 26 Oct", 400, 1200, listOf("""{"at":"2026-10-26 21:01"}"""))
        assertTrue(r.pass)
    }

    @Test fun scoresUnknownToolAndNoTool() {
        val case = JSONObject("""{"id":"y","group":"general","prompt":"hello","no_tool":true}""")
        val bad = EvalMetrics.score(case, listOf("calls.with" to JSONObject()), setOf("calls.with"), "hi", 1, 1, emptyList())
        assertFalse(bad.firstToolOk)
        val good = EvalMetrics.score(case, emptyList(), setOf("calls.with"), "hi", 1, 1, emptyList())
        assertTrue(good.pass)
        val unknown = EvalMetrics.score(JSONObject("""{"id":"z","group":"g","prompt":"p"}"""), listOf("nope.x" to JSONObject()), setOf("calls.with"), "", 1, 1, emptyList())
        assertTrue(unknown.unknownTool)
    }

    @Test fun extraNumbersWhitelistDerivedValues() {
        val (ok, _) = EvalMetrics.grounded("Total is Rs. 995.00", listOf("640.00 355.00"), setOf("995"))
        assertTrue(ok)
    }

    @Test fun expectIncludesAcceptsAnyOrder() {
        val case = JSONObject("""{"id":"p","group":"places","prompt":"save as office","expect_includes":["places.set"],"expect_args":{"label":"~office"}}""")
        val r = EvalMetrics.score(case, listOf("device.location" to JSONObject(), "places.set" to JSONObject("""{"label":"office"}""")),
            setOf("device.location", "places.set"), "Saved office.", 1, 2, emptyList())
        assertTrue(r.pass)
    }

    @Test fun csvRowQuotesCommas() {
        val r = EvalMetrics.CaseResult("a","g",true,true,true,false,true,true,emptySet(), listOf("x.y","z.w"),1,2,3,"r, with comma")
        val row = EvalMetrics.csvRow(r)
        assertEquals(EvalMetrics.csvHeader().split(",").size, row.split(",").size)
    }

    /** The golden file itself: unique ids, valid tool names, mocks for every expected tool. */
    @Test fun goldenFileIsWellFormed() {
        val f = listOf("src/main/assets/golden.json", "app/src/main/assets/golden.json").map(::File).first { it.exists() }
        val cases = JSONObject(f.readText()).getJSONArray("cases")
        val known = setOf("calls.recent","calls.missed","calls.with","sms.search","sms.recent","gmail.search","gmail.read","docs.read","sheets.read",
            "web.search","web.fetch","web.extract","web.research","device.now","device.location","reminders.create","reminders.list","reminders.delete",
            "places.list","places.set","places.add_rule","places.events","places.delete","memory.remember","memory.recall","memory.forget","memory.profile")
        val ids = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertTrue("duplicate id ${c.getString("id")}", ids.add(c.getString("id")))
            assertTrue(c.getString("prompt").isNotBlank())
            val inc = c.optJSONArray("expect_includes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            for (t in listOf(c.optString("expect_tool", "")).filter { it.isNotEmpty() } + inc) {
                assertTrue("unknown tool $t in ${c.getString("id")}", t in known)
                assertTrue("missing mock for $t in ${c.getString("id")}", c.optJSONObject("mocks")?.has(t) == true)
            }
            val traj = c.optJSONArray("expect_trajectory") ?: JSONArray()
            for (j in 0 until traj.length()) assertTrue(traj.getString(j) in known)
        }
        assertTrue(cases.length() >= 30)
    }
}
