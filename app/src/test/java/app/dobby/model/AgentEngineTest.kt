package app.dobby.model

import app.dobby.mcp.Effect
import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted backend: each call to stream() returns the next scripted reply, chunked. */
class FakeBackend(private val replies: ArrayDeque<String>) : ChatBackend {
    override val label = "fake"
    val sent = mutableListOf<String>()
    var system = ""
    override suspend fun open(systemPrompt: String) { system = systemPrompt }
    override fun stream(text: String): Flow<String> = flow {
        sent += text
        val r = replies.removeFirstOrNull() ?: "fallback"
        r.chunked(5).forEach { emit(it) }
    }
    override suspend fun oneShot(prompt: String, maxChars: Int) = "SUMMARY: s\nFACTS: []"
    override suspend fun reset() {}
    override suspend fun close() {}
}

class AgentEngineTest {
    private val tools = listOf(
        ToolSpec("calls.with", "Calls with one person.", ToolSpec.schema(listOf(Triple("who", "string", "n"), Triple("days", "integer", "d")), listOf("who"))),
        ToolSpec("calls.recent", "Recent calls.", ToolSpec.schema(listOf(Triple("days", "integer", "d")))),
        ToolSpec("memory.forget", "Delete a fact.", ToolSpec.schema(listOf(Triple("id", "integer", "id")), listOf("id")), Effect.DESTRUCTIVE),
    )
    private val entry = ModelEntry("fake", "Fake", "", "", 0.0, "cpu", true, false, "", false)

    private fun run(replies: List<String>, user: String, exec: (String, JSONObject) -> JSONObject = { n, _ -> JSONObject().put("summary", "ok from $n") }): Pair<List<ChatEvent>, FakeBackend> = runBlocking {
        val fb = FakeBackend(ArrayDeque(replies))
        val e = AgentEngine { fb }
        e.load(entry, "", tools, ToolExecutor { n, a -> exec(n, a) }, "SYS")
        e.send(user).toList() to fb
    }

    private fun text(ev: List<ChatEvent>) = ev.filterIsInstance<ChatEvent.Token>().joinToString("") { it.text }

    @Test fun toolCallThenAnswer() {
        val (ev, fb) = run(listOf("""TOOL {"name":"calls.with","args":{"who":"Mom"}}""", "You called Mom on 26 Oct."), "when did I call Mom?") { n, _ -> JSONObject().put("summary", "ok from $n: Outgoing call to Mom on Sun 26 Oct, 8:00 PM.") }
        assertEquals(listOf("calls.with"), ev.filterIsInstance<ChatEvent.ToolCall>().map { it.name })
        assertEquals("You called Mom on 26 Oct.", text(ev))
        assertTrue(fb.system.contains("TOOL {\"name\""))                      // protocol in system prompt
        assertTrue(fb.sent[0].startsWith("Tools for this request:"))          // catalog in the user turn
        assertTrue(fb.sent[1].startsWith("TOOL_RESULT calls.with: ok from calls.with"))
        assertTrue(ev.last() is ChatEvent.Done)
    }

    @Test fun heldToolLineNeverReachesTheUser() {
        val (ev, _) = run(listOf("""TOOL {"name":"calls.recent","args":{}}""", "Nothing recent."), "recent calls")
        assertFalse(text(ev).contains("TOOL"))
    }

    @Test fun mangledNumericArgIsBlockedThenCorrected() {
        val (ev, fb) = run(listOf("""TOOL {"name":"calls.recent","args":{"days":35}}""", """TOOL {"name":"calls.recent","args":{"days":365}}""", "Nobody."), "who called me 365 days ago?")
        val calls = ev.filterIsInstance<ChatEvent.ToolCall>()
        assertEquals(1, calls.size); assertEquals(365, JSONObject(calls[0].args).getInt("days"))
        assertTrue(fb.sent[1].contains("365"))
        assertTrue(ev.filterIsInstance<ChatEvent.ToolResult>().any { it.name == "validator" })
    }

    @Test fun ungroundedReplyIsRegeneratedOnceThenMarked() {
        val (ev, _) = run(listOf("""TOOL {"name":"calls.with","args":{"who":"Mom"}}""", "You called Mom for 600 seconds.", "You called Mom for 900 seconds."), "how long did I talk to Mom?") { _, _ -> JSONObject().put("summary", "Outgoing call to Mom, 10 min 0 s.") }
        assertEquals(1, ev.filterIsInstance<ChatEvent.Retract>().size)
        val t = text(ev)
        assertTrue(t, t.contains("900") && t.contains("Could not verify: 900"))
    }

    @Test fun unparsableCallGetsOneCorrectionThenShowsText() {
        val (ev, fb) = run(listOf("TOOL this is not json", "Plain answer in the end."), "hi")
        assertEquals("Plain answer in the end.", text(ev))
        assertEquals(PromptTools.CORRECTION, fb.sent[1])
    }

    @Test fun destructiveCallIsDeniedWhenHeadless() {
        Approvals.headless = true
        try {
            val (ev, fb) = run(listOf("""TOOL {"name":"memory.forget","args":{"id":7}}""", "It was not deleted."), "forget fact 7")
            assertTrue(fb.sent[1].contains("did not approve"))
            assertEquals("It was not deleted.", text(ev))
        } finally { Approvals.headless = false }
    }

    @Test fun stepCapStopsLoops() {
        val replies = List(7) { """TOOL {"name":"calls.recent","args":{}}""" }
        val (ev, _) = run(replies, "recent calls")
        assertEquals(PromptTools.MAX_STEPS, ev.filterIsInstance<ChatEvent.ToolCall>().size)
        assertTrue(text(ev).contains("stopped after"))
    }

    @Test fun noToolsMeansPlainChat() = runBlocking {
        val fb = FakeBackend(ArrayDeque(listOf("hello there")))
        val e = AgentEngine { fb }
        e.load(entry, "", emptyList(), ToolExecutor { _, _ -> JSONObject() }, "SYS")
        assertEquals("hello there", text(e.send("hi").toList()))
        assertEquals("SYS", fb.system)
    }

    @Test fun capacityFollowsRuntime() {
        assertEquals(Capacity.SMALL, Capacity.forEntry(entry))
        assertEquals(Capacity.LARGE, Capacity.forEntry(entry.copy(runtime = "remote", endpoint = "http://h/v1", modelName = "qwen3")))
        assertTrue(Capacity.LARGE.toolDataChars > Capacity.SMALL.toolDataChars && Capacity.LARGE.maxSteps > Capacity.SMALL.maxSteps)
    }

    @Test fun remoteDeltaParsing() {
        assertEquals("Hel", RemoteBackend.parseDelta("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
        assertEquals("", RemoteBackend.parseDelta("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals("full", RemoteBackend.parseDelta("""{"choices":[{"message":{"content":"full"}}]}"""))
        assertEquals(null, RemoteBackend.parseDelta("not json"))
    }
}
