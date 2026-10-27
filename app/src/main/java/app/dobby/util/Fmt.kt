package app.dobby.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Human forms the model can repeat verbatim. Tools put these in their `summary` so a small model
 * copies "Sun 26 Oct, 9:01 PM" instead of rewriting "2026-10-26 21:01" into "2026-1-0-26".
 */
object Fmt {
    var zone: TimeZone = TimeZone.getDefault()   // overridable in tests

    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }

    fun dateTime(ms: Long): String {
        val d = Date(ms)
        val now = Calendar.getInstance(zone); val c = Calendar.getInstance(zone).apply { time = d }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        return fmt(if (sameYear) "EEE d MMM, h:mm a" else "EEE d MMM yyyy, h:mm a").format(d)
    }

    fun date(ms: Long): String = fmt("EEE d MMM yyyy").format(Date(ms))

    /** "2026-10-28" style date strings → "Wed 28 Oct 2026"; anything else returned as is. */
    fun isoDate(s: String): String = runCatching {
        val p = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone; isLenient = false }
        date(p.parse(s)!!.time)
    }.getOrDefault(s)

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }

    /** Indian grouping: 132450.1 → "₹1,32,450.10"; 640.0 → "₹640". */
    fun inr(amount: Double): String {
        val neg = amount < 0; val a = Math.abs(amount)
        val whole = a.toLong(); val paise = Math.round((a - whole) * 100).toInt()
        val s = whole.toString()
        val grouped = if (s.length <= 3) s else {
            val last3 = s.takeLast(3); var rest = s.dropLast(3); val parts = mutableListOf<String>()
            while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
            if (rest.isNotEmpty()) parts.add(0, rest)
            parts.joinToString(",") + "," + last3
        }
        return (if (neg) "-₹" else "₹") + grouped + (if (paise == 0) "" else ".%02d".format(paise))
    }

    /** Relative phrases → minutes: "2 hours", "in 30 min", "1 hr 15 minutes", "tomorrow" (24h). Null if not relative. */
    fun relativeMinutes(s: String): Int? {
        val t = s.lowercase().trim()
        if (t == "tomorrow") return 24 * 60
        var total = 0; var any = false
        for (m in Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|d|day|days)\\b").findAll(t)) {
            val n = m.groupValues[1].toDouble(); any = true
            total += when (m.groupValues[2].first()) { 'h' -> (n * 60).toInt(); 'd' -> (n * 24 * 60).toInt(); else -> n.toInt() }
        }
        return if (any && total > 0) total else null
    }
}
