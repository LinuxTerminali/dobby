package app.dobby.connectors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.dobby.mcp.ToolSpec
import app.dobby.model.Capacity
import app.dobby.util.SearchParse
import app.dobby.util.TextExtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 *  - search: DuckDuckGo HTML (no key) or Brave Search (key in Settings); optional site filter
 *  - fetch: readable text of a page (boilerplate stripped, tables as rows), JS pages rendered in a hidden WebView
 *  - extract: the passages of a page that match a question
 *  - research: several query variants → merged hits → top pages read in parallel → relevant passages
 *    with numbered sources; depth 2 also follows the most relevant links on those pages
 * Limits scale with Capacity (small on-device model vs large remote model). Pages are cached per session.
 */
class BrowserConnector(private val context: Context) : Connector {
    override val id = "web"
    override val title = "Web"
    override val description = "Search the web, read pages, pull the relevant parts, research a question with sources."
    override val requirement = Requirement.None
    override fun isReady(context: Context) = true

    private val prefs = context.getSharedPreferences("web", Context.MODE_PRIVATE)
    var braveKey: String
        get() = prefs.getString("brave_key", "") ?: ""
        set(v) = prefs.edit().putString("brave_key", v).apply()

    private class Page(val text: String, val html: String?)
    private val cache = object : LinkedHashMap<String, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Page>?) = size > 12
    }
    private val fetchLimit = Semaphore(3)

    override fun tools() = listOf(
        ToolSpec("search", "Search the web. Returns titles, links and snippets. Optional site to restrict to one domain.",
            ToolSpec.schema(listOf(Triple("query", "string", "Search terms"), Triple("site", "string", "Domain only, e.g. indianrailways.gov.in"), Triple("max", "integer", "Results, default by model size")), listOf("query"))),
        ToolSpec("fetch", "Read a web page: its main text with menus and ads removed; tables come as rows. For a specific part of a long page use web.extract.",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL starting with https://")), listOf("url"))),
        ToolSpec("extract", "Pull the parts of a web page that answer a question, e.g. url=<train status page>, query='delay Davangere'. Best for long pages and tables.",
            ToolSpec.schema(listOf(Triple("url", "string", "Full URL"), Triple("query", "string", "What to look for")), listOf("url", "query"))),
        ToolSpec("research", "Answer a question from the web: several searches, the top pages read, relevant passages returned with numbered sources. depth=2 also follows the most relevant links. Use for anything factual you do not know.",
            ToolSpec.schema(listOf(Triple("question", "string", "The question"), Triple("depth", "integer", "1 (default) or 2"), Triple("max_pages", "integer", "Pages to read, default by model size")), listOf("question"))),
    )

    override suspend fun call(tool: String, args: JSONObject): JSONObject = when (tool) {
        "search" -> {
            val cap = Capacity.current
            val q = args.getString("query") + (args.optString("site", "").trim().takeIf { it.isNotEmpty() }?.let { " site:$it" } ?: "")
            val hits = search(q, args.optInt("max", cap.searchResults))
            JSONObject().put("summary", if (hits.isEmpty()) "No results for \"$q\"." else hits.withIndex().joinToString(" ") { (i, h) -> "${i + 1}. ${h.title} (${h.url}): ${h.snippet.take(200)}" })
                .put("results", JSONArray(hits.map { JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet) }))
        }
        "fetch" -> {
            val cap = Capacity.current
            val url = requireUrl(args.getString("url"))
            val page = pageText(url) ?: return err(url, "could not load the page")
            val n = cap.toolDataChars
            JSONObject().put("summary", "Page $url: ${page.take(n)}").put("url", url).put("chars", page.length)
                .put("hint", if (page.length > n) "Page is longer; use web.extract with a query for a specific part." else "")
        }
        "extract" -> {
            val cap = Capacity.current
            val url = requireUrl(args.getString("url")); val q = args.getString("query")
            val text = pageText(url) ?: return err(url, "could not load the page")
            val ps = TextExtract.passages(text, q, window = cap.passageWindow, max = cap.passagesPerPage + 2)
            JSONObject().put("summary", "From $url, parts matching \"$q\": " + TextExtract.joinPassages(ps).take(cap.toolDataChars)).put("url", url).put("matched_words", ps.maxOfOrNull { it.score } ?: 0)
        }
        "research" -> research(args.getString("question"), args.optInt("depth", 1).coerceIn(1, 2), args.optInt("max_pages", 0))
        else -> throw IllegalArgumentException("unknown tool $tool")
    }

    private suspend fun research(q: String, depth: Int, maxPagesArg: Int): JSONObject = coroutineScope {
        val cap = Capacity.current
        val maxPages = if (maxPagesArg > 0) maxPagesArg.coerceAtMost(8) else cap.researchPages
        // 1. several query variants, merged, one page per domain first
        val variants = TextExtract.queryVariants(q)
        val hits = variants.map { v -> async { runCatching { search(v, cap.searchResults) }.getOrDefault(emptyList()) } }.flatMap { it.await() }
            .distinctBy { it.url }
        val byDomain = hits.groupBy { runCatching { URL(it.url).host }.getOrDefault(it.url) }.values.map { it.first() }
        val chosen = (byDomain + hits).distinctBy { it.url }.take(maxPages)
        if (chosen.isEmpty()) return@coroutineScope JSONObject().put("summary", "No web results for \"$q\".").put("count", 0)
        // 2. read pages in parallel (3 at a time)
        val pages = chosen.map { h -> async { fetchLimit.withPermit { withTimeoutOrNull(20_000) { pageText(h.url) } }?.let { h to it } } }.mapNotNull { it.await() }
        // 3. depth 2: follow the most relevant links from the pages we read
        val extra = if (depth == 2) {
            val links = pages.flatMap { (h, _) -> htmlFor(h.url)?.let { TextExtract.relevantLinks(TextExtract.links(it, h.url), q, 2) } ?: emptyList() }
                .distinctBy { it.url }.filter { l -> pages.none { it.first.url == l.url } }.take(3)
            links.map { l -> async { fetchLimit.withPermit { withTimeoutOrNull(20_000) { pageText(l.url) } }?.let { SearchParse.Hit(l.text, l.url, "") to it } } }.mapNotNull { it.await() }
        } else emptyList()
        // 4. passages per source, numbered
        val notes = StringBuilder(); val sources = JSONArray()
        for ((i, p) in (pages + extra).withIndex()) {
            val (h, text) = p
            val ps = TextExtract.passages(text, q, window = cap.passageWindow, max = cap.passagesPerPage)
            notes.append("[${i + 1}] ${h.title} — ${h.url}\n").append(TextExtract.joinPassages(ps).take(cap.passageWindow * cap.passagesPerPage)).append("\n\n")
            sources.put(JSONObject().put("n", i + 1).put("title", h.title).put("url", h.url))
        }
        JSONObject().put("summary", "Research notes for \"$q\" (cite as [n]):\n" + notes.toString().take(cap.toolDataChars)).put("sources", sources).put("count", sources.length())
            .put("queries", JSONArray(variants))
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
    private fun htmlFor(url: String): String? = synchronized(cache) { cache[url]?.html }

    private suspend fun pageText(url: String): String? {
        synchronized(cache) { cache[url] }?.let { return it.text }
        val html = withContext(Dispatchers.IO) { httpGet(url) }
        val viaHttp = html?.let { TextExtract.mainText(it) }
        val text = if (viaHttp != null && viaHttp.length >= 400) viaHttp
            else withTimeoutOrNull(30_000) { render(url) }?.takeIf { it.isNotBlank() } ?: viaHttp
        if (text != null) synchronized(cache) { cache[url] = Page(text, html) }
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
