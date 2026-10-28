package app.dobby.mcp

import org.json.JSONObject

/** What a tool does to the world. DESTRUCTIVE calls need the user's approval unless always-allowed. */
enum class Effect { READ, WRITE, DESTRUCTIVE }

/** One tool the model can call. `parameters` is a JSON Schema object (MCP inputSchema). */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val effect: Effect = Effect.READ,
) {
    /** MCP `tools/list` shape. */
    fun toMcpJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", parameters)

    /** LiteRT-LM OpenApiTool description shape (name, description, parameters). */
    fun toOpenApiJson(): String = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("parameters", parameters)
        .toString()

    companion object {
        /** Helper to build a schema: props = name -> (type, description), required = names. */
        fun schema(props: List<Triple<String, String, String>>, required: List<String> = emptyList()): JSONObject {
            val p = JSONObject()
            for ((n, t, d) in props) p.put(n, JSONObject().put("type", t).put("description", d))
            val s = JSONObject().put("type", "object").put("properties", p)
            if (required.isNotEmpty()) s.put("required", org.json.JSONArray(required))
            return s
        }
    }
}
