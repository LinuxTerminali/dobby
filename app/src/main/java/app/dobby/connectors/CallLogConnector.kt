package app.dobby.connectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import app.dobby.mcp.ToolSpec
import app.dobby.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogConnector(private val context: Context) : Connector {
    override val id = "calls"
    override val title = "Call log"
    override val description = "Recent, missed and per-contact calls from the phone's call log."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.READ_CALL_LOG))
    override fun isReady(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    override fun tools() = listOf(
        ToolSpec("recent", "List recent calls (incoming, outgoing, missed).",
            ToolSpec.schema(listOf(
                Triple("days", "integer", "Look back this many days. Default 7"),
                Triple("limit", "integer", "Max rows. Default 20")))),
        ToolSpec("missed", "List missed calls that were not called back.",
            ToolSpec.schema(listOf(Triple("days", "integer", "Look back this many days. Default 7")))),
        ToolSpec("with", "Calls with one person, by name or number.",
            ToolSpec.schema(listOf(
                Triple("who", "string", "Contact name or phone number substring"),
                Triple("days", "integer", "Look back this many days. Default 30")), listOf("who"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject {
        val days = args.optInt("days", if (tool == "with") 30 else 7)
        val limit = args.optInt("limit", 20)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val rows = query(since, args.optString("who", null))
        val out = when (tool) {
            "recent" -> rows.take(limit)
            "missed" -> {
                val missed = rows.filter { it.type == CallLog.Calls.MISSED_TYPE }
                missed.filter { m -> rows.none { it.number == m.number && it.date > m.date && it.type == CallLog.Calls.OUTGOING_TYPE } }
            }
            "with" -> rows.take(limit)
            else -> throw IllegalArgumentException("unknown tool $tool")
        }
        val summary = if (out.isEmpty()) "No calls found." else out.take(8).joinToString(" ") { it.sentence() } + if (out.size > 8) " (+${out.size - 8} more)" else ""
        return JSONObject().put("summary", summary).put("calls", JSONArray(out.map { it.toJson() })).put("count", out.size)
    }

    private data class Row(val name: String?, val number: String, val type: Int, val date: Long, val seconds: Long) {
        fun toJson() = JSONObject()
            .put("name", name ?: JSONObject.NULL).put("number", number)
            .put("type", when (type) { CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "other" })
            .put("at", Fmt.dateTime(date)).put("duration_s", seconds)
        fun sentence(): String {
            val who = name ?: number
            val kind = when (type) { CallLog.Calls.INCOMING_TYPE -> "Incoming call from"; CallLog.Calls.OUTGOING_TYPE -> "Outgoing call to"; CallLog.Calls.MISSED_TYPE -> "Missed call from"; CallLog.Calls.REJECTED_TYPE -> "Rejected call from"; else -> "Call with" }
            return "$kind $who on ${Fmt.dateTime(date)}" + (if (seconds > 0) ", ${Fmt.duration(seconds)}." else ".")
        }
    }

    private fun query(since: Long, who: String?): List<Row> {
        val cols = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val sel = StringBuilder("${CallLog.Calls.DATE} > ?")
        val argsL = mutableListOf(since.toString())
        if (!who.isNullOrBlank()) {
            sel.append(" AND (${CallLog.Calls.CACHED_NAME} LIKE ? OR ${CallLog.Calls.NUMBER} LIKE ?)")
            argsL += "%$who%"; argsL += "%$who%"
        }
        val out = mutableListOf<Row>()
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, sel.toString(), argsL.toTypedArray(), "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) out += Row(c.getString(0), c.getString(1) ?: "", c.getInt(2), c.getLong(3), c.getLong(4))
        }
        return out
    }

}
