package app.dobby.connectors

import android.content.Context
import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only web access. A full WebView/automation tool is a later step. */
class BrowserConnector : Connector {
    override val id = "web"
    override val title = "Browser (read-only)"
    override val description = "Fetch a web page and return its text."
    override val requirement = Requirement.None
    override fun isReady(context: Context) = true

    override fun tools() = listOf(
        ToolSpec("fetch", "Fetch a URL and return readable text (max 8000 chars).",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL starting with https://")), listOf("url"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(tool == "fetch") { "unknown tool $tool" }
        val url = args.getString("url")
        require(url.startsWith("http")) { "url must start with http(s)://" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Dobby/0.1")
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 30000
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        JSONObject().put("status", code).put("url", url).put("text", stripHtml(raw).take(8000))
    }

    companion object {
        fun stripHtml(html: String): String = html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
    }
}
