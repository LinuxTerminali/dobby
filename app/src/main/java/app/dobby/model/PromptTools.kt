package app.dobby.model

import app.dobby.mcp.ToolSpec
import org.json.JSONObject

/**
 * Prompt-based tool calling: the model sees a compact catalog and replies with one line
 *   TOOL {"name":"calls.with","args":{"who":"Mom"}}
 * when it needs a tool. Bypasses the runtime's native tool path entirely.
 * Pure Kotlin + org.json so it is unit-tested on the JVM.
 */
object PromptTools {
    const val MAX_STEPS = 5
    private val CALL = Regex("(?m)^\\s*(?:```(?:json)?\\s*)?TOOL\\s*(\\{.*\\})\\s*(?:```)?\\s*$", RegexOption.DOT_MATCHES_ALL)

    data class Call(val name: String, val args: JSONObject)

    /** One line per tool: name(param*: type, …) — description. `*` marks required. */
    fun catalog(tools: List<ToolSpec>): String = tools.joinToString("\n") { t ->
        val props = t.parameters.optJSONObject("properties")
        val req = t.parameters.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        // org.json does not keep key order: required first, then alphabetical, so the catalog is stable.
        val keys = props?.keys()?.asSequence()?.toList()?.sortedWith(compareBy({ it !in req }, { it })) ?: emptyList()
        val params = keys.joinToString(", ") { k ->
            val type = props!!.getJSONObject(k).optString("type", "string")
            "$k${if (k in req) "*" else ""}: $type"
        }
        "- ${t.name}($params) — ${t.description.take(140)}"
    }

    fun protocol(tools: List<ToolSpec>): String = """
        Tools. To use one, reply with exactly one line and nothing else:
        TOOL {"name":"<tool>","args":{...}}
        Then you will get a line starting with TOOL_RESULT; use it to answer the user in plain words,
        repeating names, numbers and dates exactly as they appear in the result.
        Do not call a tool when none is needed. Do not invent tool names. `*` marks a required argument.
        ${catalog(tools)}
    """.trimIndent()

    /** The last TOOL line in the model's reply, if any, provided the name is one we offered. */
    fun parse(reply: String, offered: Set<String>): Call? {
        val m = CALL.findAll(reply).lastOrNull() ?: return null
        val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: return null
        val name = json.optString("name").trim()
        if (name !in offered) return null
        return Call(name, json.optJSONObject("args") ?: JSONObject())
    }

    /** Text safe to show so far: everything before the first line that starts with TOOL. */
    fun visiblePrefix(buffer: String): String {
        val i = Regex("(?m)^\\s*(?:```(?:json)?\\s*)?TOOL\\b").find(buffer)?.range?.first ?: return buffer
        return buffer.substring(0, i)
    }

    fun resultMessage(name: String, result: JSONObject): String =
        "TOOL_RESULT $name: " + result.toString().take(4000)
}
