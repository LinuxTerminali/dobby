package app.dobby.connectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.dobby.mcp.ToolSpec
import app.dobby.reminders.Reminder
import app.dobby.reminders.ReminderScheduler
import app.dobby.reminders.ReminderStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Lets the model set, list and delete reminders, including scheduled checks (reminders with a prompt). */
class RemindersConnector(private val context: Context) : Connector {
    override val id = "reminders"
    override val title = "Reminders"
    override val description = "One-off and recurring reminders; recurring checks that wake the model."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
    override fun isReady(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private val store = ReminderStore(context)
    private val scheduler = ReminderScheduler(context)

    override fun tools() = listOf(
        ToolSpec("create", "Create a reminder. Kinds: (a) place-based, 'when I reach/leave X' → place=X, event=enter|exit; (b) recurring → days (1=Sun..7=Sat; weekdays=[2,3,4,5,6]) + hour + minute; (c) one-off → in_minutes (e.g. 'in 2 hours' → 120) or at (local time 'YYYY-MM-DD HH:MM' or 'HH:MM' for today/tomorrow). title is the text to show.",
            ToolSpec.schema(listOf(
                Triple("title", "string", "Short title"),
                Triple("message", "string", "Body text. Optional"),
                Triple("hour", "integer", "0-23, for recurring"),
                Triple("minute", "integer", "0-59, for recurring"),
                Triple("days", "array", "Days of week as integers 1..7 (1=Sunday). Empty for one-off"),
                Triple("in_minutes", "integer", "One-off: minutes from now"),
                Triple("at", "string", "One-off: local time 'YYYY-MM-DD HH:MM' or 'HH:MM'"),
                Triple("place", "string", "Saved place label for a place-based reminder, e.g. office"),
                Triple("event", "string", "enter or exit, for a place-based reminder"),
                Triple("prompt", "string", "Optional question to run with tools at trigger time")), listOf("title"))),
        ToolSpec("list", "List reminders.", ToolSpec.schema(emptyList())),
        ToolSpec("delete", "Delete a reminder by id.", ToolSpec.schema(listOf(Triple("id", "string", "Reminder id")), listOf("id"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "create" -> if (args.optString("place").isNotBlank()) createPlaceReminder(args) else {
            val daysArr = args.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.filter { it in 1..7 }
            val at = resolveAt(args)
            require(days.isNotEmpty() || at > System.currentTimeMillis()) { "give days+hour+minute for recurring; in_minutes or at for one-off; place+event for place-based" }
            val cal = java.util.Calendar.getInstance().apply { if (at > 0) timeInMillis = at }
            val r = Reminder(
                id = UUID.randomUUID().toString().take(8), title = args.getString("title"), message = args.optString("message"),
                hour = if (days.isNotEmpty()) args.optInt("hour", 9) else cal.get(java.util.Calendar.HOUR_OF_DAY),
                minute = if (days.isNotEmpty()) args.optInt("minute", 0) else cal.get(java.util.Calendar.MINUTE),
                days = days, atMillis = at, prompt = args.optString("prompt"))
            store.upsert(r); scheduler.schedule(r)
            JSONObject().put("id", r.id).put("scheduled", r.describe()).put("next", r.nextTrigger())
        }
        "list" -> JSONObject().put("reminders", JSONArray(store.all().map { it.toJson().put("when", it.describe()) }))
        "delete" -> { val id = args.getString("id"); scheduler.cancel(id); store.delete(id); JSONObject().put("deleted", id) }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }

    /** One-off time from in_minutes, at ('YYYY-MM-DD HH:MM' or 'HH:MM', next occurrence), or at_epoch_millis. */
    private fun resolveAt(args: JSONObject): Long {
        val now = System.currentTimeMillis()
        args.optInt("in_minutes", 0).takeIf { it > 0 }?.let { return now + it * 60_000L }
        val at = args.optString("at", "").trim()
        if (at.isNotEmpty()) {
            val cal = java.util.Calendar.getInstance()
            val m = Regex("^(?:(\\d{4})-(\\d{2})-(\\d{2})[ T])?(\\d{1,2}):(\\d{2})$").find(at) ?: throw IllegalArgumentException("at must be 'YYYY-MM-DD HH:MM' or 'HH:MM'")
            val (y, mo, d, h, mi) = m.destructured
            if (y.isNotEmpty()) cal.set(y.toInt(), mo.toInt() - 1, d.toInt())
            cal.set(java.util.Calendar.HOUR_OF_DAY, h.toInt()); cal.set(java.util.Calendar.MINUTE, mi.toInt()); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            if (y.isEmpty() && cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return cal.timeInMillis
        }
        return args.optLong("at_epoch_millis", 0)
    }

    /** "Remind me to X when I reach office" → a plain-message rule on the place. */
    private suspend fun createPlaceReminder(args: JSONObject): JSONObject {
        val placeStore = app.dobby.location.PlaceStore(context)
        val label = args.getString("place")
        val p = placeStore.byLabel(label) ?: throw IllegalArgumentException(
            "No saved place named '$label'. Saved places: ${placeStore.all().map { it.label }.ifEmpty { listOf("none") }.joinToString()}. " +
            "Ask the user to say 'save this place as $label' when they are there, or give lat/lng via places.set.")
        val event = args.optString("event", "enter").lowercase().let { if (it == "exit" || it == "leave") "exit" else "enter" }
        val rule = app.dobby.location.PlaceRule(UUID.randomUUID().toString().take(8), event, emptyList(), 0, 24,
            args.getString("title"), args.optString("prompt"), args.optString("message").ifBlank { args.getString("title") })
        placeStore.upsert(p.copy(rules = p.rules + rule))
        return JSONObject().put("id", rule.id).put("scheduled", "on $event ${p.label}").put("place", p.label)
    }
}
