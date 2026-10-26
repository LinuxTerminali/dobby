package app.dobby.connectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.dobby.mcp.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsConnector(private val context: Context) : Connector {
    override val id = "sms"
    override val title = "SMS"
    override val description = "Search and read SMS (bank alerts, bills, OTPs are hidden)."
    override val requirement = Requirement.Permissions(listOf(Manifest.permission.READ_SMS))
    override fun isReady(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override fun tools() = listOf(
        ToolSpec("search", "Search SMS bodies and senders for a word or phrase.",
            ToolSpec.schema(listOf(
                Triple("query", "string", "Text to match, e.g. 'BESCOM' or 'debited'"),
                Triple("days", "integer", "Look back this many days. Default 30"),
                Triple("limit", "integer", "Max rows. Default 20")), listOf("query"))),
        ToolSpec("recent", "Most recent SMS.",
            ToolSpec.schema(listOf(Triple("limit", "integer", "Max rows. Default 20")))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 20)
        val days = args.optInt("days", 30)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val q = if (tool == "search") args.getString("query") else null
        val sel = StringBuilder("${Telephony.Sms.DATE} > ?")
        val sa = mutableListOf(since.toString())
        if (q != null) { sel.append(" AND (${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"); sa += "%$q%"; sa += "%$q%" }
        val arr = JSONArray()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            sel.toString(), sa.toTypedArray(), "${Telephony.Sms.DATE} DESC")?.use { c ->
            while (c.moveToNext() && arr.length() < limit) {
                val body = c.getString(1) ?: ""
                if (OTP.containsMatchIn(body)) continue   // never hand OTPs to the model
                arr.put(JSONObject().put("from", c.getString(0)).put("at", FMT.format(Date(c.getLong(2)))).put("body", body))
            }
        }
        return JSONObject().put("messages", arr).put("count", arr.length())
    }

    companion object {
        private val OTP = Regex("\\bOTP\\b|one[- ]time[- ]pass", RegexOption.IGNORE_CASE)
        private val FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}
