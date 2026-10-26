package app.dobby.reminders

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A reminder. Recurring when `days` is non-empty (Calendar.SUNDAY=1 … SATURDAY=7),
 * one-off when `days` is empty and `atMillis` is set. If `prompt` is set, Dobby wakes
 * the model at that time, runs the prompt with the active tools, and posts the answer
 * as the notification (a scheduled check, not just a ping).
 */
data class Reminder(
    val id: String,
    val title: String,
    val message: String = "",
    val hour: Int,
    val minute: Int,
    val days: List<Int> = emptyList(),
    val atMillis: Long = 0,
    val prompt: String = "",
    val enabled: Boolean = true,
) {
    val recurring get() = days.isNotEmpty()

    /** Next trigger strictly after `now`. Null when a one-off is already past. */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long? {
        if (!recurring) return if (atMillis > now) atMillis else null
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        for (i in 0..7) {
            if (c.timeInMillis > now && c.get(Calendar.DAY_OF_WEEK) in days) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("message", message)
        .put("hour", hour).put("minute", minute).put("days", JSONArray(days)).put("at_millis", atMillis)
        .put("prompt", prompt).put("enabled", enabled)

    fun describe(): String {
        val t = "%02d:%02d".format(hour, minute)
        return if (recurring) "$t on " + days.sorted().joinToString(",") { DAY[it] ?: "?" } else "once at ${java.util.Date(atMillis)}"
    }

    companion object {
        val DAY = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
        fun fromJson(o: JSONObject): Reminder {
            val d = o.optJSONArray("days") ?: JSONArray()
            return Reminder(o.getString("id"), o.getString("title"), o.optString("message"), o.getInt("hour"), o.getInt("minute"),
                (0 until d.length()).map { d.getInt(it) }, o.optLong("at_millis"), o.optString("prompt"), o.optBoolean("enabled", true))
        }
    }
}
