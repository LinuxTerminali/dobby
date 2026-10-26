package app.dobby.location

import android.content.Context
import org.json.JSONArray

class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun all(): List<Place> { val a = JSONArray(prefs.getString("places", "[]")); return (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) } }
    fun get(id: String) = all().firstOrNull { it.id == id }
    fun byLabel(label: String) = all().firstOrNull { it.label.equals(label, ignoreCase = true) }
    fun upsert(p: Place) = save(all().filter { it.id != p.id } + p)
    fun delete(id: String) = save(all().filter { it.id != id })
    private fun save(list: List<Place>) = prefs.edit().putString("places", JSONArray(list.map { it.toJson() }).toString()).apply()

    /** Last 200 enter/exit events. No continuous track is kept. */
    fun events(): List<LocationEvent> { val a = JSONArray(prefs.getString("events", "[]")); return (0 until a.length()).map { LocationEvent.fromJson(a.getJSONObject(it)) } }
    fun addEvent(e: LocationEvent) {
        val list = (events() + e).takeLast(200)
        prefs.edit().putString("events", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
