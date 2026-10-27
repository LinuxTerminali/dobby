package app.dobby.model

import app.dobby.mcp.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptToolsTest {
    private val tools = listOf(
        ToolSpec("calls.with", "Calls with one person.", ToolSpec.schema(listOf(Triple("who", "string", "name"), Triple("days", "integer", "lookback")), listOf("who"))),
        ToolSpec("device.now", "Current time.", ToolSpec.schema(emptyList())),
    )
    private val offered = tools.map { it.name }.toSet()

    @Test fun catalogMarksRequiredAndTypes() {
        val c = PromptTools.catalog(tools)
        assertTrue(c, c.contains("- calls.with(who*: string, days: integer) — Calls with one person."))
        assertTrue(c, c.contains("- device.now() — Current time."))
    }

    @Test fun parsesPlainToolLine() {
        val call = PromptTools.parse("""TOOL {"name":"calls.with","args":{"who":"Mom"}}""", offered)!!
        assertEquals("calls.with", call.name); assertEquals("Mom", call.args.getString("who"))
    }

    @Test fun parsesFencedAndPrefixedToolLine() {
        val call = PromptTools.parse("Sure.\n```json\nTOOL {\"name\": \"device.now\", \"args\": {}}\n```", offered)!!
        assertEquals("device.now", call.name)
    }

    @Test fun rejectsUnknownToolAndPlainText() {
        assertNull(PromptTools.parse("""TOOL {"name":"nope.x","args":{}}""", offered))
        assertNull(PromptTools.parse("You called Mom on 26 Oct.", offered))
        assertNull(PromptTools.parse("TOOL not json", offered))
    }

    @Test fun visiblePrefixHoldsBackToolLine() {
        assertEquals("Let me check.\n", PromptTools.visiblePrefix("Let me check.\nTOOL {\"name\":"))
        assertEquals("plain answer", PromptTools.visiblePrefix("plain answer"))
        assertEquals("", PromptTools.visiblePrefix("TOOL {"))
    }

    @Test fun resultMessageIsBounded() {
        val big = JSONObject().put("text", "x".repeat(10_000))
        assertTrue(PromptTools.resultMessage("web.fetch", big).length < 4100)
    }
}
