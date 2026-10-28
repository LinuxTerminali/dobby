package app.dobby.connectors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Read-only web access through a hidden WebView, so JavaScript-rendered pages work and the
 * request looks like a real browser. Returns the page's visible text. Sites with bot protection
 * or logins may still refuse; those need the user in the loop.
 */
class BrowserConnector(private val context: Context) : Connector {
    override val id = "web"
    override val title = "Browser (read-only)"
    override val description = "Open a web page in a hidden browser and return its visible text."
    override val requirement = Requirement.None
    override fun isReady(context: Context) = true

    override fun tools() = listOf(
        ToolSpec("fetch", "Open a URL in a browser and return the visible text (max 8000 chars). Good for articles, docs, timetables. Not for sites that need login or payment.",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL starting with https://")), listOf("url"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject {
        require(tool == "fetch") { "unknown tool $tool" }
        val url = args.getString("url")
        require(url.startsWith("http")) { "url must start with http(s)://" }
        val text = withTimeoutOrNull(30_000) { render(url) } ?: return JSONObject().put("url", url).put("error", "timed out after 30s")
        return JSONObject().put("summary", "Page $url says: ${text.take(700)}").put("url", url).put("text", text.take(8000))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")  // drop the WebView marker
            var done = false
            val handler = Handler(Looper.getMainLooper())
            fun finish(result: String) {
                if (done) return
                done = true
                runCatching { wv.stopLoading(); wv.destroy() }
                if (cont.isActive) cont.resume(result)
            }
            fun extract() {
                wv.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { v ->
                    val s = if (v == null || v == "null") "" else runCatching { org.json.JSONTokener(v).nextValue().toString() }.getOrDefault(v)
                    finish(s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim())
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    // Give JS-rendered pages a moment to paint before reading the DOM.
                    handler.postDelayed({ extract() }, 1500)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            }
            cont.invokeOnCancellation { handler.post { finish("") } }
            wv.loadUrl(url)
        }
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
