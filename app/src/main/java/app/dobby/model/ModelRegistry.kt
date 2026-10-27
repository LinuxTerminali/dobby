package app.dobby.model

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Built-in list from assets plus models the user adds by URL. */
class ModelRegistry(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    /** App-private folder: deleted on uninstall. */
    val privateDir: File get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    /** Shared folder: survives uninstall. Readable only with "All files access". */
    val sharedDir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dobby/models")
    fun sharedAllowed() = Environment.isExternalStorageManager()
    val modelsDir: File get() = if (sharedAllowed()) sharedDir.apply { mkdirs() } else privateDir

    /** Move any models from the private folder into the shared one (after the permission is granted). */
    fun migrateToShared(): Int {
        if (!sharedAllowed()) return 0
        sharedDir.mkdirs()
        var n = 0
        privateDir.listFiles()?.filter { it.isFile && it.length() > 1_000_000 }?.forEach { f ->
            val dst = File(sharedDir, f.name)
            if (dst.exists() && dst.length() == f.length()) { f.delete(); return@forEach }
            val ok = f.renameTo(dst) || runCatching { f.copyTo(dst, overwrite = true); f.delete(); true }.getOrDefault(false)
            if (ok) n++
        }
        return n
    }

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

    /** Prefer the shared copy when it exists, else the private one. */
    fun fileFor(e: ModelEntry): File {
        val shared = File(sharedDir, e.file)
        return if (sharedAllowed() && shared.exists()) shared else File(privateDir, e.file).let { if (it.exists() || !sharedAllowed()) it else shared }
    }
    fun isDownloaded(e: ModelEntry) = fileFor(e).let { it.exists() && it.length() > 1_000_000 }

    var selectedId: String?
        get() = prefs.getString("selected", null)
        set(v) = prefs.edit().putString("selected", v).apply()

    var hfToken: String
        get() = prefs.getString("hf_token", "") ?: ""
        set(v) = prefs.edit().putString("hf_token", v).apply()
}
