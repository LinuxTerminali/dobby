package app.dobby.eval

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure metric logic, no Android dependencies, so it is unit-tested on the JVM.
 */
object EvalMetrics {

    /** Numbers in free text, Indian grouping removed: "₹1,32,450.10" -> "132450.10", "21:01" -> "21","01". */
    fun numbers(text: String): Set<String> =
        Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(text).map { normalise(it.value) }.toSet()

    fun normalise(n: String): String {
        val s = n.replace(",", "")
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s.trimStart('0').ifEmpty { "0" }
    }

    /** Every number in the reply must appear in the sources (prompt + tool results) or `extra`. Ignores 0-12 (counts, hours). */
    fun grounded(reply: String, sources: List<String>, extra: Set<String> = emptySet()): Pair<Boolean, Set<String>> {
        val allowed = sources.flatMap { numbers(it) }.toSet() + extra.map { normalise(it) }
        val bad = numbers(reply).filter { it !in allowed && (it.toDoubleOrNull() ?: 99.0) > 12 }.toSet()
        return bad.isEmpty() to bad
    }

    /** `expected` is a subset: every key present must match (case-insensitive for strings; substring match when value starts with '~'). */
    fun argsMatch(expected: JSONObject?, actual: JSONObject): Boolean {
        if (expected == null) return true
        for (k in expected.keys()) {
            if (!actual.has(k)) return false
            val e = expected.get(k); val a = actual.get(k)
            val ok = when {
                e is String && e.startsWith("~") -> a.toString().contains(e.drop(1), ignoreCase = true)
                e is String -> a.toString().equals(e, ignoreCase = true)
                e is Number && a is Number -> e.toDouble() == a.toDouble()
                e is JSONArray && a is JSONArray -> e.toString() == a.toString()
                else -> e.toString() == a.toString()
            }
            if (!ok) return false
        }
        return true
    }

    data class CaseResult(
        val id: String, val group: String,
        val firstToolOk: Boolean, val argsOk: Boolean, val trajectoryOk: Boolean, val unknownTool: Boolean,
        val replyOk: Boolean, val grounded: Boolean, val badNumbers: Set<String>,
        val calls: List<String>, val ttftMs: Long, val totalMs: Long, val replyChars: Int, val reply: String,
        val firstArgs: String = "",
    ) {
        val pass get() = firstToolOk && argsOk && trajectoryOk && !unknownTool && replyOk && grounded
    }

    fun score(case: JSONObject, calls: List<Pair<String, JSONObject>>, offered: Set<String>, reply: String,
              ttftMs: Long, totalMs: Long, mockTexts: List<String>): CaseResult {
        val expectTool = case.optString("expect_tool", "")
        val noTool = case.optBoolean("no_tool", false)
        val first = calls.firstOrNull()
        val includes = case.optJSONArray("expect_includes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val firstToolOk = when {
            noTool -> calls.isEmpty()
            includes.isNotEmpty() -> includes.all { inc -> calls.any { it.first == inc } }
            expectTool.isEmpty() -> true
            else -> first?.first == expectTool
        }
        // Args are checked on the expected tool's call (first call when expect_tool, the included call otherwise).
        val target = when {
            expectTool.isNotEmpty() -> first?.takeIf { it.first == expectTool }
            includes.isNotEmpty() -> calls.firstOrNull { it.first == includes.first() }
            else -> null
        }
        val argsOk = when {
            noTool -> true
            expectTool.isEmpty() && includes.isEmpty() -> true
            target == null -> false
            else -> argsMatch(case.optJSONObject("expect_args"), target.second)
        }
        val traj = case.optJSONArray("expect_trajectory")
        val trajectoryOk = traj == null || (0 until traj.length()).map { traj.getString(it) } == calls.map { it.first }
        val unknown = calls.any { it.first !in offered }
        val contains = case.optString("reply_contains", "")
        val replyOk = contains.isEmpty() || reply.contains(contains, ignoreCase = true)
        val extra = case.optJSONArray("extra_numbers")?.let { a -> (0 until a.length()).map { a.get(it).toString() }.toSet() } ?: emptySet()
        val (g, bad) = grounded(reply, listOf(case.getString("prompt")) + mockTexts, extra)
        return CaseResult(case.getString("id"), case.optString("group", "general"), firstToolOk, argsOk, trajectoryOk, unknown,
            replyOk, g, bad, calls.map { it.first }, ttftMs, totalMs, reply.length, reply, first?.second?.toString() ?: "")
    }

    fun csvHeader() = "id,group,pass,first_tool_ok,args_ok,trajectory_ok,unknown_tool,reply_ok,grounded,bad_numbers,calls,first_args,ttft_ms,total_ms,reply_chars"
    fun csvRow(r: CaseResult) = listOf(r.id, r.group, r.pass, r.firstToolOk, r.argsOk, r.trajectoryOk, r.unknownTool, r.replyOk, r.grounded,
        r.badNumbers.joinToString(" "), r.calls.joinToString(">"), r.firstArgs, r.ttftMs, r.totalMs, r.replyChars).joinToString(",") { q(it.toString()) }
    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun summary(results: List<CaseResult>, model: String): String {
        val n = results.size.coerceAtLeast(1)
        fun pct(f: (CaseResult) -> Boolean) = "%.0f%%".format(100.0 * results.count(f) / n)
        val byGroup = results.groupBy { it.group }.entries.joinToString("  ") { (g, rs) -> "$g ${rs.count { it.pass }}/${rs.size}" }
        return "$model · ${results.count { it.pass }}/${results.size} pass\n" +
            "tool ${pct { it.firstToolOk }} · args ${pct { it.argsOk }} · trajectory ${pct { it.trajectoryOk }} · unknown-tool ${pct { it.unknownTool }} · grounded ${pct { it.grounded }}\n" +
            "ttft p50 ${median(results.map { it.ttftMs })} ms · total p50 ${median(results.map { it.totalMs })} ms\n$byGroup"
    }

    fun median(xs: List<Long>): Long = if (xs.isEmpty()) 0 else xs.sorted()[xs.size / 2]
}
