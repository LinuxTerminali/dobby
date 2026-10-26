package app.dobby.reminders

import android.content.Context
import org.json.JSONArray

class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun all(): List<Reminder> {
        val arr = JSONArray(prefs.getString("items", "[]"))
        return (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
    }
    fun get(id: String) = all().firstOrNull { it.id == id }
    fun upsert(r: Reminder) = save(all().filter { it.id != r.id } + r)
    fun delete(id: String) = save(all().filter { it.id != id })
    private fun save(list: List<Reminder>) =
        prefs.edit().putString("items", JSONArray(list.map { it.toJson() }).toString()).apply()
}
