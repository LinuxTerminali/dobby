package app.dobby.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes MCP calls to registered servers. Tools are exposed as "<serverId>.<tool>"
 * to the model so names never collide. Handles the two MCP methods the app needs.
 */
class McpRouter(private val servers: () -> List<McpServer>) {

    fun listTools(): List<ToolSpec> = servers().flatMap { s ->
        s.tools().map { t -> t.copy(name = "${s.id}.${t.name}") }
    }

    suspend fun callTool(qualifiedName: String, args: JSONObject): JSONObject {
        val dot = qualifiedName.indexOf('.')
        require(dot > 0) { "tool name must be <server>.<tool>: $qualifiedName" }
        val sid = qualifiedName.substring(0, dot)
        val tool = qualifiedName.substring(dot + 1)
        val server = servers().firstOrNull { it.id == sid }
            ?: throw IllegalArgumentException("unknown server: $sid")
        return server.call(tool, args)
    }

    /** JSON-RPC 2.0 handler for `tools/list` and `tools/call` (for a future local MCP endpoint). */
    suspend fun handleJsonRpc(request: JSONObject): JSONObject {
        val id = request.opt("id")
        val result = try {
            when (request.optString("method")) {
                "tools/list" -> JSONObject().put("tools", JSONArray(listTools().map { it.toMcpJson() }))
                "tools/call" -> {
                    val params = request.getJSONObject("params")
                    val out = callTool(params.getString("name"), params.optJSONObject("arguments") ?: JSONObject())
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", out.toString())))
                }
                else -> return JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", JSONObject().put("code", -32601).put("message", "method not found"))
            }
        } catch (e: Exception) {
            return JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32000).put("message", e.message ?: e.toString()))
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }
}
