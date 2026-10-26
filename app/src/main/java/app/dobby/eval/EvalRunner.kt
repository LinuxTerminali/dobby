package app.dobby.eval

import app.dobby.App
import app.dobby.model.ChatEvent
import app.dobby.model.EngineHolder
import app.dobby.model.ModelEntry
import app.dobby.model.ToolExecutor
import org.json.JSONObject

/**
 * Runs assets/golden.json through a model with the active tools and reports
 * pass rate and latency. Cases: {"prompt", "expect_substring"?, "expect_tool"?}.
 * Extend the golden set with your own SMS/bill cases; keep real data out of git.
 */
class EvalRunner(private val app: App) {
    suspend fun run(entry: ModelEntry): String {
        val cases = JSONObject(app.assets.open("golden.json").bufferedReader().readText()).getJSONArray("cases")
        val engine = EngineHolder.get(app)
        val tools = app.router.listTools()
        engine.load(entry, app.models.fileFor(entry).absolutePath, tools,
            ToolExecutor { n, a -> app.router.callTool(n, a) },
            "You are a test harness subject. Use tools when they help. Answer briefly.")
        var pass = 0; var totalMs = 0L
        val lines = StringBuilder()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            engine.resetConversation()
            val out = StringBuilder(); val called = mutableListOf<String>(); var ms = 0L
            engine.send(c.getString("prompt")).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> out.append(ev.text)
                    is ChatEvent.ToolCall -> called += ev.name
                    is ChatEvent.Done -> ms = ev.millis
                    else -> {}
                }
            }
            val okSub = c.optString("expect_substring", "").let { it.isEmpty() || out.contains(it, ignoreCase = true) }
            val okTool = c.optString("expect_tool", "").let { it.isEmpty() || called.any { t -> t.endsWith(it) } }
            val ok = okSub && okTool
            if (ok) pass++
            totalMs += ms
            lines.append(if (ok) "PASS " else "FAIL ").append(c.getString("prompt").take(40)).append(" · ").append(ms).append("ms")
            if (!ok) lines.append(" · tools=").append(called).append(" · out=").append(out.toString().take(80).replace('\n', ' '))
            lines.append('\n')
        }
        return "${entry.name}: $pass/${cases.length()} pass · avg ${if (cases.length() > 0) totalMs / cases.length() else 0} ms\n$lines"
    }
}
