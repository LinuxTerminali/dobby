package app.dobby.util

import org.json.JSONArray

/** Day-of-week parsing for reminders. */
object Days {
    /** Accepts [2,3], ["mon","tue"], "weekdays", "weekends", "daily", "mon,wed". Returns Calendar days 1=Sun..7=Sat. */
    fun parse(v: Any?): List<Int> {
        val names = mapOf("sun" to 1, "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7)
        fun one(x: Any?): List<Int> = when (x) {
            null -> emptyList()
            is Number -> listOf(x.toInt()).filter { it in 1..7 }
            is JSONArray -> (0 until x.length()).flatMap { one(x.get(it)) }
            else -> x.toString().lowercase().split(Regex("[,\\s]+")).flatMap { w ->
                when (w) {
                    "weekdays", "weekday" -> listOf(2, 3, 4, 5, 6)
                    "weekends", "weekend" -> listOf(1, 7)
                    "daily", "everyday", "every" -> listOf(1, 2, 3, 4, 5, 6, 7)
                    else -> names[w.take(3)]?.let { listOf(it) } ?: w.toIntOrNull()?.takeIf { it in 1..7 }?.let { listOf(it) } ?: emptyList()
                }
            }
        }
        return one(v).distinct().sorted()
    }
}
