package app.dobby.connectors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.dobby.mcp.ToolSpec
import app.dobby.util.SearchParse
import app.dobby.util.TextExtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Web tools built so the code does the heavy lifting and the model only reads:
 *  - search: DuckDuckGo HTML (no key) or Brave Search (key in Settings) → titles, urls, snippets
 *  - fetch: readable text of a page (boilerplate stripped), JS pages rendered in a hidden WebView
 *  - extract: the passages of a page that match a question, not the first N chars
 *  - research: search → read top pages → relevant passages with sources, in one call
 * Pages are cached per session so follow-up questions do not refetch.
 */
class BrowserConnector(private val context: Context) : Connector {
    override val id = "web"
    override val title = "Web"
    override val description = "Search the web, read pages, pull the relevant parts, research a question."
    override val requirement = Requirement.None
    override fun isReady(context: Context) = true

    private val prefs = context.getSharedPreferences("web", Context.MODE_PRIVATE)
    var braveKey: String
        get() = prefs.getString("brave_key", "") ?: ""
        set(v) = prefs.edit().putString("brave_key", v).apply()

    private val cache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 6
    }

    override fun tools() = listOf(
        ToolSpec("search", "Search the web. Returns titles, links and snippets. Use before fetch when you do not have a URL.",
            ToolSpec.schema(listOf(Triple("query", "string", "Search terms"), Triple("max", "integer", "Results, default 5")), listOf("query"))),
        ToolSpec("fetch", "Read a web page: its main text with menus and ads removed (first ~1500 chars). For a specific part of a long page use web.extract.",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL starting with https://")), listOf("url"))),
        ToolSpec("extract", "Pull the parts of a web page that answer a question, e.g. url=<train status page>, query='delay Davangere'. Best for long pages and tables.",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL"), Triple("query", "string", "What to look for")), listOf("url", "query"))),
        ToolSpec("research", "Answer a question from the web: searches, reads the top pages, returns the relevant passages with sources. Use for anything factual you do not know.",
            ToolSpec.schema(listOf(Triple("question", "string", "The question"), Triple("max_pages", "integer", "Pages to read, default 3")), listOf("question"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "search" -> {
            val q = args.getString("query"); val hits = search(q, args.optInt("max", 5))
            JSONObject().put("summary", if (hits.isEmpty()) "No results for \"$q\"." else hits.withIndex().joinToString(" ") { (i, h) -> "${i + 1}. ${h.title} (${h.url}): ${h.snippet.take(160)}" })
                .put("results", JSONArray(hits.map { JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet) }))
        }
        "fetch" -> {
            val url = requireUrl(args.getString("url"))
            val text = pageText(url) ?: return err(url, "could not load the page")
            JSONObject().put("summary", "Page $url: ${text.take(1500)}").put("url", url).put("chars", text.length)
                .put("hint", if (text.length > 1500) "Page is longer; use web.extract with a query for a specific part." else "")
        }
        "extract" -> {
            val url = requireUrl(args.getString("url")); val q = args.getString("query")
            val text = pageText(url) ?: return err(url, "could not load the page")
            val ps = TextExtract.passages(text, q, window = 450, max = 4)
            JSONObject().put("summary", "From $url, parts matching \"$q\": " + TextExtract.joinPassages(ps).take(1800)).put("url", url).put("matched_words", ps.maxOfOrNull { it.score } ?: 0)
        }
        "research" -> {
            val q = args.getString("question"); val n = args.optInt("max_pages", 3).coerceIn(1, 4)
            val hits = search(q, n + 2).take(n)
            if (hits.isEmpty()) JSONObject().put("summary", "No web results for \"$q\".").put("count", 0)
            else {
                val notes = StringBuilder(); val sources = JSONArray()
                for ((i, h) in hits.withIndex()) {
                    val text = withTimeoutOrNull(20_000) { pageText(h.url) } ?: continue
                    val ps = TextExtract.passages(text, q, window = 350, max = 2)
                    notes.append("Source ${i + 1}: ${h.title} (${h.url})\n").append(TextExtract.joinPassages(ps).take(700)).append("\n\n")
                    sources.put(JSONObject().put("title", h.title).put("url", h.url))
                }
                JSONObject().put("summary", "Research notes for \"$q\" (cite the source numbers):\n" + notes.toString().take(2400)).put("sources", sources).put("count", sources.length())
            }
        }
        else -> throw IllegalArgumentException("unknown tool $tool")
    }

    private fun requireUrl(u: String): String { require(u.startsWith("http")) { "url must start with http(s)://" }; return u }
    private fun err(url: String, why: String) = JSONObject().put("url", url).put("error", why)

    // ---- search ----
    suspend fun search(q: String, max: Int): List<SearchParse.Hit> = withContext(Dispatchers.IO) {
        if (braveKey.isNotBlank()) runCatching { brave(q, max) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val enc = URLEncoder.encode(q, "UTF-8")
        for (u in listOf("https://html.duckduckgo.com/html/?q=$enc", "https://lite.duckduckgo.com/lite/?q=$enc")) {
            val html = httpGet(u) ?: continue
            val hits = SearchParse.parse(html, max)
            if (hits.isNotEmpty()) return@withContext hits
        }
        emptyList()
    }

    private fun brave(q: String, max: Int): List<SearchParse.Hit> {
        val c = URL("https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(q, "UTF-8")}&count=$max").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Subscription-Token", braveKey); c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (c.responseCode != 200) return emptyList()
        val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { SearchParse.Hit(it.optString("title"), it.optString("url"), it.optString("description")) }
    }

    // ---- pages ----
    private suspend fun pageText(url: String): String? {
        synchronized(cache) { cache[url] }?.let { return it }
        val viaHttp = withContext(Dispatchers.IO) { httpGet(url)?.let { TextExtract.mainText(it) } }
        val text = if (viaHttp != null && viaHttp.length >= 400) viaHttp
            else withTimeoutOrNull(30_000) { render(url) }?.takeIf { it.isNotBlank() } ?: viaHttp
        if (text != null) synchronized(cache) { cache[url] = text }
        return text
    }

    private fun httpGet(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "text/html,*/*"); c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        c.instanceFollowRedirects = true; c.connectTimeout = 12000; c.readTimeout = 20000
        if (c.responseCode !in 200..299) return null
        c.inputStream.bufferedReader().readText()
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun render(url: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
            var done = false
            val handler = Handler(Looper.getMainLooper())
            fun finish(result: String) {
                if (done) return
                done = true
                runCatching { wv.stopLoading(); wv.destroy() }
                if (cont.isActive) cont.resume(result)
            }
            fun extract() {
                wv.evaluateJavascript("(function(){var r=document.querySelector('main, article, [role=main]')||document.body;return r?r.innerText:''})()") { v ->
                    val s = if (v == null || v == "null") "" else runCatching { org.json.JSONTokener(v).nextValue().toString() }.getOrDefault(v)
                    finish(s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim())
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) { handler.postDelayed({ extract() }, 1500) }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            }
            cont.invokeOnCancellation { handler.post { finish("") } }
            wv.loadUrl(url)
        }
    }

    companion object {
        fun stripHtml(html: String): String = TextExtract.mainText(html)
    }
}
