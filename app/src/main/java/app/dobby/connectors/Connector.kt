package app.dobby.connectors

import android.content.Context
import app.dobby.mcp.McpServer

/** What a connector needs before its tools can run. */
sealed class Requirement {
    data class Permissions(val permissions: List<String>) : Requirement()
    data class GoogleOAuth(val scopes: List<String>) : Requirement()
    object None : Requirement()
}

/**
 * A connector = an MCP server + how to onboard it + an on/off switch.
 * To add a connector: implement this and register it in ConnectorRegistry.
 */
interface Connector : McpServer {
    val title: String
    val description: String
    val requirement: Requirement
    /** True when permissions/auth are in place. */
    fun isReady(context: Context): Boolean
}

/** Persisted enabled/disabled state. */
class ConnectorState(context: Context) {
    private val prefs = context.getSharedPreferences("connectors", Context.MODE_PRIVATE)
    fun isEnabled(id: String) = prefs.getBoolean(id, false)
    fun setEnabled(id: String, v: Boolean) = prefs.edit().putBoolean(id, v).apply()
}
