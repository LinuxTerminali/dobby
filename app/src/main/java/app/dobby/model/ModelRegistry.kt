package app.dobby.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Built-in list from assets plus models the user adds by URL. */
class ModelRegistry(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    val modelsDir: File get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun builtIn(): List<ModelEntry> {
        val json = context.assets.open("models.json").bufferedReader().readText()
        val arr = JSONObject(json).getJSONArray("models")
        return (0 until arr.length()).map { ModelEntry.fromJson(arr.getJSONObject(it)) }
    }

    fun custom(): List<ModelEntry> {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        return (0 until arr.length()).map { ModelEntry.fromJson(arr.getJSONObject(it), custom = true) }
    }

    fun all(): List<ModelEntry> = builtIn() + custom()

    fun addCustom(e: ModelEntry) {
        val list = custom().filter { it.id != e.id } + e.copy(custom = true)
        prefs.edit().putString("custom", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun removeCustom(id: String) {
        prefs.edit().putString("custom", JSONArray(custom().filter { it.id != id }.map { it.toJson() }).toString()).apply()
    }

    fun fileFor(e: ModelEntry) = File(modelsDir, e.file)
    fun isDownloaded(e: ModelEntry) = fileFor(e).let { it.exists() && it.length() > 1_000_000 }

    var selectedId: String?
        get() = prefs.getString("selected", null)
        set(v) = prefs.edit().putString("selected", v).apply()

    var hfToken: String
        get() = prefs.getString("hf_token", "") ?: ""
        set(v) = prefs.edit().putString("hf_token", v).apply()
}
