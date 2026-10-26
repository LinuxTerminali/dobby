package app.dobby.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class Fact(val id: Long, val text: String, val tags: String, val createdAt: Long, val source: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("tags", tags).put("source", source)
}

/**
 * Long-term memory: a profile (a few lines about the user) and tagged facts.
 * Facts are short statements the user told Dobby or confirmed. Lookup is keyword-based;
 * only a handful are injected per message, because a 4B model has a small context.
 */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 1) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fact(id INTEGER PRIMARY KEY, text TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '', created_at INTEGER, source TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    // ---- profile (a few lines, always in the prompt) ----
    var profile: String
        get() = prefs.getString("profile", "") ?: ""
        set(v) = prefs.edit().putString("profile", v.trim()).apply()

    // ---- facts ----
    fun remember(text: String, tags: String, source: String = "user"): Fact {
        val cv = ContentValues().apply {
            put("text", text.trim()); put("tags", normTags(tags)); put("created_at", System.currentTimeMillis()); put("source", source)
        }
        val id = writableDatabase.insert("fact", null, cv)
        return Fact(id, text.trim(), normTags(tags), System.currentTimeMillis(), source)
    }

    fun forget(id: Long) = writableDatabase.delete("fact", "id=?", arrayOf(id.toString())) > 0

    fun all(): List<Fact> = query("1=1", emptyArray())

    /** Keyword match on text and tags; ranks by number of query words hit. */
    fun recall(query: String, limit: Int = 5): List<Fact> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return all().takeLast(limit)
        val scored = all().map { f ->
            val hay = (f.text + " " + f.tags).lowercase()
            f to words.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        return scored.take(limit).map { it.first }
    }

    private fun query(where: String, args: Array<String>): List<Fact> {
        val out = mutableListOf<Fact>()
        readableDatabase.query("fact", null, where, args, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) out += Fact(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4) ?: "")
        }
        return out
    }

    private fun normTags(t: String) = t.lowercase().split(Regex("[,\\s]+")).filter { it.isNotBlank() }.distinct().joinToString(",")
}
