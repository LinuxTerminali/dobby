package app.dobby.connectors

import android.content.Context
import app.dobby.mcp.ToolSpec
import app.dobby.memory.MemoryStore
import org.json.JSONArray
import org.json.JSONObject

class MemoryConnector(private val store: MemoryStore) : Connector {
    override val id = "memory"
    override val title = "Memory"
    override val description = "Facts about you that Dobby keeps between sessions (on the phone only)."
    override val requirement = Requirement.None
    override fun isReady(context: Context) = true

    override fun tools() = listOf(
        ToolSpec("remember", "Save a short fact about the user for later. Example: text='Office map pin is wrong; use 12.934,77.611', tags='office,cab'.",
            ToolSpec.schema(listOf(Triple("text", "string", "One sentence"), Triple("tags", "string", "Comma-separated keywords")), listOf("text"))),
        ToolSpec("recall", "Find saved facts matching keywords.",
            ToolSpec.schema(listOf(Triple("query", "string", "Keywords"), Triple("limit", "integer", "Max facts. Default 5")), listOf("query"))),
        ToolSpec("forget", "Delete a fact by id.", ToolSpec.schema(listOf(Triple("id", "integer", "Fact id")), listOf("id"))),
        ToolSpec("profile", "Read the user's profile lines.", ToolSpec.schema(emptyList())),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "remember" -> store.remember(args.getString("text"), args.optString("tags", ""), "model").let { it.toJson().put("summary", "Saved: \"${it.text}\" (id ${it.id}).") }
        "recall" -> { val f = store.recall(args.getString("query"), args.optInt("limit", 5)); JSONObject().put("summary", if (f.isEmpty()) "No saved facts match." else f.joinToString(" ") { "\"${it.text}\" (id ${it.id})." }).put("facts", JSONArray(f.map { it.toJson() })) }
        "forget" -> JSONObject().put("deleted", store.forget(args.getLong("id")))
        "profile" -> JSONObject().put("profile", store.profile)
        else -> throw IllegalArgumentException("unknown tool $tool")
    }
}
