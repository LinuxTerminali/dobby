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
        ToolSpec("create", "Create a reminder. Three kinds: (a) place-based, 'when I reach/leave X' → place=X, event=enter|exit; (b) recurring at a time → days (1=Sun..7=Sat) + hour + minute; (c) one-off → at_epoch_millis. Use message for the text to show. Only add prompt if Dobby must look something up when it fires.",
            ToolSpec.schema(listOf(
                Triple("title", "string", "Short title"),
                Triple("message", "string", "Body text. Optional"),
                Triple("hour", "integer", "0-23, for recurring"),
                Triple("minute", "integer", "0-59, for recurring"),
                Triple("days", "array", "Days of week as integers 1..7 (1=Sunday). Empty for one-off"),
                Triple("at_epoch_millis", "integer", "Trigger time for a one-off reminder"),
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
            val at = args.optLong("at_epoch_millis", 0)
            require(days.isNotEmpty() || at > System.currentTimeMillis()) { "give days+hour+minute for recurring, a future at_epoch_millis for one-off, or place+event for place-based" }
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
