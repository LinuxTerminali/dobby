package app.dobby.model

import app.dobby.mcp.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptToolsTest {
    private val tools = listOf(
        ToolSpec("calls.with", "Calls with one person.", ToolSpec.schema(listOf(Triple("who", "string", "name"), Triple("days", "integer", "lookback")), listOf("who"))),
        ToolSpec("memory.remember", "Save a fact.", ToolSpec.schema(listOf(Triple("text", "string", "fact"), Triple("tags", "string", "tags")), listOf("text"))),
        ToolSpec("memory.recall", "Find facts.", ToolSpec.schema(listOf(Triple("query", "string", "q"), Triple("limit", "integer", "n")), listOf("query"))),
        ToolSpec("device.now", "Current time.", ToolSpec.schema(emptyList())),
    )

    @Test fun catalogMarksRequiredAndTypes() {
        val c = PromptTools.catalog(tools)
        assertTrue(c, c.contains("- calls.with(who*: string, days: integer) — Calls with one person."))
        assertTrue(c, c.contains("- device.now() — Current time."))
    }

    @Test fun parsesJsonToolLine() {
        val call = PromptTools.parse("""TOOL {"name":"calls.with","args":{"who":"Mom"}}""", tools)!!
        assertEquals("calls.with", call.name); assertEquals("Mom", call.args.getString("who"))
    }

    @Test fun parsesFencedAndPrefixedJsonLine() {
        val call = PromptTools.parse("Sure.\n```json\nTOOL {\"name\": \"device.now\", \"args\": {}}\n```", tools)!!
        assertEquals("device.now", call.name)
    }

    @Test fun parsesFunctionStylePositional() {
        val call = PromptTools.parse("""memory.remember("office map pin is wrong, use the north gate", "office")""", tools)!!
        assertEquals("memory.remember", call.name)
        assertEquals("office map pin is wrong, use the north gate", call.args.getString("text"))
        assertEquals("office", call.args.getString("tags"))
    }

    @Test fun parsesGemmaNativeStyleNamedArgs() {
        val call = PromptTools.parse("""<|tool_call>call:memory.recall(query: "office", limit: 3)<tool_call|>""", tools)!!
        assertEquals("memory.recall", call.name)
        assertEquals("office", call.args.getString("query"))
        assertEquals(3, call.args.getInt("limit"))
    }

    @Test fun rejectsUnknownToolAndPlainText() {
        assertNull(PromptTools.parse("""TOOL {"name":"nope.x","args":{}}""", tools))
        assertNull(PromptTools.parse("You called Mom on 26 Oct.", tools))
        assertNull(PromptTools.parse("TOOL not json", tools))
        assertNull(PromptTools.parse("nope.x(1)", tools))
    }

    @Test fun looksLikeCallDetectsAttempts() {
        assertTrue(PromptTools.looksLikeCall("TOOL not json"))
        assertTrue(PromptTools.looksLikeCall("<|tool_call>call:x.y()"))
        assertTrue(PromptTools.looksLikeCall("calls.with(\"Mom\")"))
        assertFalse(PromptTools.looksLikeCall("You called Mom. Version 2.0 (beta)"))
    }

    @Test fun visiblePrefixHoldsBackToolLine() {
        assertEquals("Let me check.\n", PromptTools.visiblePrefix("Let me check.\nTOOL {\"name\":"))
        assertEquals("plain answer", PromptTools.visiblePrefix("plain answer"))
        assertEquals("", PromptTools.visiblePrefix("TOOL {"))
        assertEquals("", PromptTools.visiblePrefix("<|tool_call>call:memory.recall(query: \"office\")"))
    }

    @Test fun streamingHoldsTrailingSingleWord() {
        assertEquals("", PromptTools.visiblePrefix("memory", final = false))
        assertEquals("", PromptTools.visiblePrefix("memory.remember", final = false))
        assertEquals("Sure. memory", PromptTools.visiblePrefix("Sure. memory", final = false))  // mid-line word: not a call start
        assertEquals("Sure.\n", PromptTools.visiblePrefix("Sure.\nmemory", final = false))
        assertEquals("You called Mom", PromptTools.visiblePrefix("You called Mom", final = false))
        assertEquals("memory", PromptTools.visiblePrefix("memory", final = true))
    }

    @Test fun turnMessageCarriesCatalog() {
        val m = PromptTools.turnMessage("hi", tools.take(1))
        assertTrue(m.startsWith("Tools for this request:\n- calls.with(") && m.endsWith("\n\nhi"))
        assertEquals("hi", PromptTools.turnMessage("hi", emptyList()))
    }

    @Test fun resultMessagePutsSummaryFirst() {
        val r = JSONObject().put("summary", "Outgoing call to Mom on Sun 26 Oct, 8:00 PM, 10 min 0 s.").put("count", 1)
        val m = PromptTools.resultMessage("calls.with", r)
        assertTrue(m, m.startsWith("TOOL_RESULT calls.with: Outgoing call to Mom on Sun 26 Oct, 8:00 PM, 10 min 0 s.\nDATA: "))
        assertFalse(m, m.substringAfter("DATA: ").contains("summary"))
    }

    @Test fun resultMessageIsBounded() {
        val big = JSONObject().put("text", "x".repeat(10_000))
        assertTrue(PromptTools.resultMessage("web.fetch", big).length < 4100)
    }
}

class ToolRouterTest {
    private fun spec(n: String) = ToolSpec(n, "d", ToolSpec.schema(emptyList()))
    private val all = listOf("calls.recent","calls.missed","calls.with","sms.search","sms.recent","gmail.search","gmail.read","docs.read","sheets.read",
        "web.search","web.fetch","web.extract","web.research","device.now","device.location","reminders.create","reminders.list","reminders.delete",
        "places.list","places.set","places.add_rule","places.events","places.delete","memory.remember","memory.recall","memory.forget","memory.profile").map(::spec)

    @Test fun routesByKeyword() {
        val names = ToolRouter.route("Did I miss any calls this week?", all).map { it.name }
        assertTrue(names.toString(), "calls.missed" in names && "memory.recall" in names && "web.fetch" !in names)
        assertTrue(ToolRouter.route("Remind me to eat an apple when I reach office", all).map { it.name }.containsAll(listOf("reminders.create", "places.list")))
        assertEquals(listOf("web.fetch", "memory.recall"), ToolRouter.route("Summarise this page: https://example.com/x", all).map { it.name }.filter { it != "web.search" && it != "web.extract" && it != "web.research" })
        assertTrue(ToolRouter.route("what is the status of train 16589", all).map { it.name }.contains("web.research"))
    }

    @Test fun fallsBackToAllWhenNothingMatches() {
        assertEquals(all.size, ToolRouter.route("hello", all).size)
        assertEquals(all.size, ToolRouter.route("what can you do?", all).size)
    }

    @Test fun keywordsMatchWholeWords() {
        assertTrue(ToolRouter.groupsFor("I called Mom").contains("calls"))
        assertFalse(ToolRouter.groupsFor("recall the plan").contains("calls"))
    }
}
