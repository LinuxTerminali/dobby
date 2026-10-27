package app.dobby.connectors

import android.Manifest
import android.content.Context
import app.dobby.location.GeofenceManager
import app.dobby.location.Place
import app.dobby.location.PlaceRule
import app.dobby.location.PlaceStore
import app.dobby.mcp.Effect
import app.dobby.mcp.ToolSpec
import app.dobby.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Named places with geofence rules, and the enter/exit history. */
class PlacesConnector(private val context: Context) : Connector {
    override val id = "places"
    override val title = "Places & location"
    override val description = "Home, office and other places; wakes Dobby on enter/exit. No continuous tracking."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    private val store = PlaceStore(context)
    private val geo = GeofenceManager(context)
    override fun isReady(context: Context) = geo.hasForeground() && geo.hasBackground()

    override fun tools() = listOf(
        ToolSpec("list", "List saved places and their rules.", ToolSpec.schema(emptyList())),
        ToolSpec("set", "Save or update a place. Omit lat/lng to use the phone's current location.",
            ToolSpec.schema(listOf(Triple("label", "string", "e.g. home, office"), Triple("lat", "number", "Latitude"), Triple("lng", "number", "Longitude"),
                Triple("radius_m", "number", "Radius in metres. Default 150")), listOf("label"))),
        ToolSpec("add_rule", "Do something when the user enters or leaves a saved place. Give message for a plain notification, or prompt if Dobby must check something with tools.",
            ToolSpec.schema(listOf(Triple("label", "string", "Place label"), Triple("event", "string", "enter or exit"),
                Triple("days", "array", "Days 1=Sun..7=Sat; empty = any"), Triple("from_hour", "integer", "0-23, default 0"), Triple("to_hour", "integer", "1-24, default 24"),
                Triple("title", "string", "Notification title"), Triple("message", "string", "Plain notification text"), Triple("prompt", "string", "Optional: a question Dobby should answer with tools when it fires")), listOf("label", "event", "title"))),
        ToolSpec("events", "Recent enter/exit events.", ToolSpec.schema(listOf(Triple("days", "integer", "Look back. Default 7")))),
        ToolSpec("delete", "Delete a place by label.", ToolSpec.schema(listOf(Triple("label", "string", "Place label")), listOf("label")), Effect.DESTRUCTIVE),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "list" -> { val all = store.all(); JSONObject().put("summary", if (all.isEmpty()) "No saved places." else all.joinToString(" ") { p -> "${p.label}" + (if (p.rules.isEmpty()) "." else " with rules: " + p.rules.joinToString("; ") { it.describe() } + ".") })
            .put("places", JSONArray(all.map { it.toJson().put("rules_text", JSONArray(it.rules.map { r -> r.describe() })) })) }
        "set" -> {
            val label = args.getString("label")
            val (lat, lng) = if (args.has("lat") && args.has("lng")) args.getDouble("lat") to args.getDouble("lng")
                else geo.currentLocation() ?: throw IllegalStateException("no current location; give lat/lng")
            val existing = store.byLabel(label)
            val p = Place(existing?.id ?: UUID.randomUUID().toString().take(8), label, lat, lng, args.optDouble("radius_m", 150.0).toFloat(), existing?.rules ?: emptyList())
            store.upsert(p); geo.registerAll()
            p.toJson().put("summary", "Saved place \"${p.label}\" at %.5f, %.5f.".format(p.lat, p.lng))
        }
        "add_rule" -> {
            val p = store.byLabel(args.getString("label")) ?: throw IllegalArgumentException(noPlace(args.getString("label")))
            val d = args.optJSONArray("days") ?: JSONArray()
            val rule = PlaceRule(UUID.randomUUID().toString().take(8), args.getString("event"), (0 until d.length()).map { d.getInt(it) },
                args.optInt("from_hour", 0), args.optInt("to_hour", 24), args.getString("title"), args.optString("prompt"), args.optString("message"))
            store.upsert(p.copy(rules = p.rules + rule))
            rule.toJson().put("summary", "Rule added to ${p.label}: ${rule.describe()}.")
        }
        "events" -> {
            val since = System.currentTimeMillis() - args.optInt("days", 7) * 86_400_000L
            val evs = store.events().filter { it.at > since }
            JSONObject().put("summary", if (evs.isEmpty()) "No enter/exit events." else evs.takeLast(10).joinToString(" ") { "${it.type.replaceFirstChar(Char::uppercase)} ${it.label} on ${Fmt.dateTime(it.at)}." })
                .put("events", JSONArray(evs.map { it.toJson().put("at_text", Fmt.dateTime(it.at)) }))
        }
        "delete" -> { val p = store.byLabel(args.getString("label")); if (p != null) { store.delete(p.id); geo.registerAll() }; JSONObject().put("deleted", p != null) }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }

    private fun noPlace(label: String): String {
        val known = store.all().map { it.label }
        return "No saved place named '$label'. Saved places: ${if (known.isEmpty()) "none" else known.joinToString()}. " +
            "Ask the user to say 'save this place as $label' when they are there (places.set uses the current location), or give lat/lng."
    }
}
