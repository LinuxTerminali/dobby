package app.dobby.location

import org.json.JSONArray
import org.json.JSONObject

/** A rule attached to a place: when an enter/exit happens inside the window, run the prompt. */
data class PlaceRule(
    val id: String, val event: String /* enter|exit */, val days: List<Int> /* 1=Sun..7=Sat, empty=any */,
    val fromHour: Int = 0, val toHour: Int = 24, val title: String, val prompt: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("event", event).put("days", JSONArray(days))
        .put("from_hour", fromHour).put("to_hour", toHour).put("title", title).put("prompt", prompt)
    fun describe() = "$event ${if (days.isEmpty()) "any day" else days.joinToString(",") { DAY[it] ?: "?" }} $fromHour:00–$toHour:00 → $title"
    companion object {
        val DAY = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
        fun fromJson(o: JSONObject): PlaceRule {
            val d = o.optJSONArray("days") ?: JSONArray()
            return PlaceRule(o.getString("id"), o.getString("event"), (0 until d.length()).map { d.getInt(it) },
                o.optInt("from_hour", 0), o.optInt("to_hour", 24), o.optString("title"), o.optString("prompt"))
        }
    }
}

data class Place(val id: String, val label: String, val lat: Double, val lng: Double, val radiusM: Float = 150f, val rules: List<PlaceRule> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("lat", lat).put("lng", lng)
        .put("radius_m", radiusM.toDouble()).put("rules", JSONArray(rules.map { it.toJson() }))
    companion object {
        fun fromJson(o: JSONObject): Place {
            val r = o.optJSONArray("rules") ?: JSONArray()
            return Place(o.getString("id"), o.getString("label"), o.getDouble("lat"), o.getDouble("lng"),
                o.optDouble("radius_m", 150.0).toFloat(), (0 until r.length()).map { PlaceRule.fromJson(r.getJSONObject(it)) })
        }
    }
}

data class LocationEvent(val placeId: String, val label: String, val type: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("place_id", placeId).put("label", label).put("type", type).put("at", at)
    companion object { fun fromJson(o: JSONObject) = LocationEvent(o.getString("place_id"), o.getString("label"), o.getString("type"), o.getLong("at")) }
}
