package app.dobby.connectors

import android.content.Context
import android.util.Base64
import app.dobby.mcp.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class GmailConnector(private val context: Context, private val auth: GoogleAuth) : Connector {
    override val id = "gmail"
    override val title = "Gmail"
    override val description = "Search and read email (read-only)."
    override val requirement = Requirement.GoogleOAuth(listOf(GoogleAuth.SCOPE_GMAIL))
    override fun isReady(context: Context) = auth.isSignedIn() && GoogleAuth.SCOPE_GMAIL in auth.grantedScopes()

    override fun tools() = listOf(
        ToolSpec("search", "Search email with Gmail query syntax, e.g. 'from:bescom newer_than:30d'.",
            ToolSpec.schema(listOf(
                Triple("query", "string", "Gmail search query"),
                Triple("limit", "integer", "Max messages. Default 10")), listOf("query"))),
        ToolSpec("read", "Read one email by id (from gmail.search).",
            ToolSpec.schema(listOf(Triple("id", "string", "Message id")), listOf("id"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "search" -> {
            val q = URLEncoder.encode(args.getString("query"), "UTF-8")
            val limit = args.optInt("limit", 10)
            val list = JSONObject(auth.get("$BASE/messages?q=$q&maxResults=$limit"))
            val ids = list.optJSONArray("messages") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until ids.length()) {
                val m = JSONObject(auth.get("$BASE/messages/${ids.getJSONObject(i).getString("id")}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"))
                val h = headers(m)
                out.put(JSONObject().put("id", m.getString("id")).put("from", h["From"]).put("subject", h["Subject"]).put("date", h["Date"]).put("snippet", m.optString("snippet")))
            }
            JSONObject().put("messages", out).put("count", out.length())
        }
        "read" -> {
            val m = JSONObject(auth.get("$BASE/messages/${args.getString("id")}?format=full"))
            val h = headers(m)
            JSONObject().put("id", m.getString("id")).put("from", h["From"]).put("subject", h["Subject"]).put("date", h["Date"])
                .put("body", bodyText(m.getJSONObject("payload")).take(8000))
        }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }

    private fun headers(m: JSONObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val hs = m.optJSONObject("payload")?.optJSONArray("headers") ?: return out
        for (i in 0 until hs.length()) { val h = hs.getJSONObject(i); out[h.getString("name")] = h.getString("value") }
        return out
    }

    private fun bodyText(part: JSONObject): String {
        val mime = part.optString("mimeType")
        val data = part.optJSONObject("body")?.optString("data", "") ?: ""
        if (mime == "text/plain" && data.isNotEmpty()) return decode(data)
        val parts = part.optJSONArray("parts")
        if (parts != null) {
            var html = ""
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val t = bodyText(p)
                if (p.optString("mimeType") == "text/html") html = t else if (t.isNotBlank()) return t
            }
            if (html.isNotBlank()) return html
        }
        if (mime == "text/html" && data.isNotEmpty()) return BrowserConnector.stripHtml(decode(data))
        return ""
    }

    private fun decode(b64url: String) = String(Base64.decode(b64url, Base64.URL_SAFE), Charsets.UTF_8)

    companion object { private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me" }
}
