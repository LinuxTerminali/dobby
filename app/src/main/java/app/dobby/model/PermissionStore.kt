package app.dobby.model

import android.content.Context

/** Always-allow rules per tool. */
class PermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("permissions", Context.MODE_PRIVATE)
    fun isAlwaysAllowed(tool: String) = prefs.getBoolean("allow:$tool", false)
    fun setAlwaysAllowed(tool: String, v: Boolean) = prefs.edit().putBoolean("allow:$tool", v).apply()
    fun allAllowed(): List<String> = prefs.all.keys.filter { it.startsWith("allow:") && prefs.getBoolean(it, false) }.map { it.removePrefix("allow:") }
}
