package app.dobby.mcp

import org.json.JSONObject

/**
 * An in-process MCP server: a named group of tools. Every connector implements this.
 * The router speaks MCP JSON-RPC shapes (tools/list, tools/call) over these.
 */
interface McpServer {
    val id: String
    fun tools(): List<ToolSpec>
    /** Execute a tool. Return a JSON object; throw on error. */
    suspend fun call(tool: String, args: JSONObject): JSONObject
}
