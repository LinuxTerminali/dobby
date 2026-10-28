package app.dobby.model

import app.dobby.mcp.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt-based tool calling: the model sees a compact catalog and replies with one line
 *   TOOL {"name":"calls.with","args":{"who":"Mom"}}
 * when it needs a tool. Bypasses the runtime's native tool path entirely.
 * The parser also accepts the shapes small models drift into:
 *   calls.with("Mom")            calls.with(who: "Mom", days: 30)
 *   <|tool_call>call:calls.with(who: "Mom")<tool_call|>
 * Pure Kotlin + org.json so it is unit-tested on the JVM.
 */
object PromptTools {
    const val MAX_STEPS = 5
    /** Tool data fed back to the model. Long results grow the context fast on a phone. */
    const val DATA_CAP = 1500
    private val JSON_LINE = Regex("(?m)^\\s*(?:```(?:json)?\\s*)?TOOL\\s*(\\{.*\\})\\s*(?:```)?\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val FUNC = Regex("(?:<\\|tool_call>\\s*call:\\s*)?([a-z_]+\\.[a-z_]+)\\s*\\((.*?)\\)\\s*(?:<tool_call\\|>)?", RegexOption.DOT_MATCHES_ALL)
    private val HOLD = Regex("(?m)^\\s*(?:```(?:json)?\\s*)?(?:TOOL\\b|<\\|tool_call>|[a-z_]+\\.[a-z_]+\\s*\\()")

    data class Call(val name: String, val args: JSONObject)

    /** Parameter order used everywhere: required first, then alphabetical (org.json drops key order). */
    fun paramOrder(t: ToolSpec): List<String> {
        val props = t.parameters.optJSONObject("properties") ?: return emptyList()
        val req = required(t)
        return props.keys().asSequence().toList().sortedWith(compareBy({ it !in req }, { it }))
    }

    private fun required(t: ToolSpec): Set<String> =
        t.parameters.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()

    /** One line per tool: name(param*: type, …) — description. `*` marks required. */
    fun catalog(tools: List<ToolSpec>): String = tools.joinToString("\n") { t ->
        val props = t.parameters.optJSONObject("properties")
        val req = required(t)
        val params = paramOrder(t).joinToString(", ") { k ->
            "$k${if (k in req) "*" else ""}: ${props!!.getJSONObject(k).optString("type", "string")}"
        }
        "- ${t.name}($params) — ${t.description.take(160)}"
    }

    /** System-prompt part: the rules only. The catalog travels with each user message (see [turnMessage]). */
    fun protocol(): String = """
        You can answer directly, do arithmetic, and extract or reformat text the user gives you without any tool.
        When you need information or an action from the phone, use a tool: reply with exactly one line and nothing else:
        TOOL {"name":"<tool>","args":{...}}
        Example: TOOL {"name":"calls.with","args":{"who":"Mom"}}
        You will then get a line starting with TOOL_RESULT; use it to answer in plain words, repeating names,
        numbers and dates exactly as they appear. Do not call a tool when none is needed. `*` marks a required argument.
        Each message lists the tools available for that request. If a result is empty or an error, try one other
        relevant tool before saying you could not find it. Give only the arguments that apply; leave the rest out.
        When asked how much in total, add the amounts and state the total. Use memory.remember only when the user asks you to
        remember something or states a lasting fact about themselves (a place, an account, a preference); never for
        passing events such as what they spent or did today.
    """.trimIndent()

    /** The user turn with the tools offered for it. */
    fun turnMessage(userText: String, offered: List<ToolSpec>): String =
        if (offered.isEmpty()) userText else "Tools for this request:\n${catalog(offered)}\n\n$userText"

    /** True when the text contains something that looks like an attempted tool call. */
    fun looksLikeCall(text: String) = HOLD.containsMatchIn(text)

    /** The last tool call in the model's reply, if any, provided the name is one we offered. */
    fun parse(reply: String, tools: List<ToolSpec>): Call? {
        val byName = tools.associateBy { it.name }
        JSON_LINE.findAll(reply).lastOrNull()?.let { m ->
            val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
            val name = json?.optString("name")?.trim()
            if (name != null && name in byName) return Call(name, json.optJSONObject("args") ?: JSONObject())
        }
        FUNC.findAll(reply).lastOrNull { it.groupValues[1] in byName }?.let { m ->
            val spec = byName.getValue(m.groupValues[1])
            return Call(spec.name, parseArgs(m.groupValues[2], spec))
        }
        return null
    }

    /** `who: "Mom", days: 30` or `"Mom", 30` (positional → paramOrder) or `who="Mom"`. */
    internal fun parseArgs(raw: String, spec: ToolSpec): JSONObject {
        val out = JSONObject()
        val order = paramOrder(spec)
        var pos = 0
        for (piece in splitTopLevel(raw)) {
            val p = piece.trim(); if (p.isEmpty()) continue
            val kv = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(p)
            val (key, value) = if (kv != null) kv.groupValues[1] to kv.groupValues[2] else (order.getOrNull(pos++) ?: "arg$pos") to p
            out.put(key, coerce(value.trim(), spec, key))
        }
        return out
    }

    private fun coerce(v: String, spec: ToolSpec, key: String): Any {
        val type = spec.parameters.optJSONObject("properties")?.optJSONObject(key)?.optString("type", "") ?: ""
        if (v.startsWith("[")) return runCatching { JSONArray(v) }.getOrDefault(v)
        val s = v.trim('"', '\'', '`')
        if (type == "integer") s.toIntOrNull()?.let { return it }
        if (type == "number") s.toDoubleOrNull()?.let { return it }
        if (type == "boolean") s.lowercase().toBooleanStrictOrNull()?.let { return it }
        return s
    }

    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder()
        var depth = 0; var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> { cur.append(c); if (c == quote) quote = null }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '[' || c == '{' -> { depth++; cur.append(c) }
                c == ']' || c == '}' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    /**
     * Text safe to show so far: everything before the first line that starts like a tool call.
     * While streaming (final = false) a trailing line that is still a single word is held too,
     * because "memory" + ".remember" + "(" arrive as separate chunks.
     */
    fun visiblePrefix(buffer: String, final: Boolean = true): String {
        HOLD.find(buffer)?.let { return buffer.substring(0, it.range.first) }
        if (final) return buffer
        val lineStart = buffer.lastIndexOf('\n') + 1
        val last = buffer.substring(lineStart)
        return if (last.isNotEmpty() && !last.contains(' ')) buffer.substring(0, lineStart) else buffer
    }

    /** Summary line first (the model copies it), then the data for details. */
    fun resultMessage(name: String, result: JSONObject): String {
        val summary = result.optString("summary", "")
        if (summary.isEmpty()) return "TOOL_RESULT $name: " + result.toString().take(DATA_CAP)
        val data = JSONObject(result.toString()).apply { remove("summary") }.toString()
        return "TOOL_RESULT $name: $summary\nDATA: " + data.take(DATA_CAP)
    }

    const val CORRECTION = "That was not a valid tool call. Reply with exactly one line: TOOL {\"name\":\"<tool>\",\"args\":{...}} using a tool from the list, or answer the user directly."
}
