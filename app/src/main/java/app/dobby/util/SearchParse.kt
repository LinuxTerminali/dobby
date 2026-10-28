package app.dobby.util

/** Parses DuckDuckGo's HTML/lite results page into (title, url, snippet). Pure Kotlin, tested on a fixture. */
object SearchParse {
    data class Hit(val title: String, val url: String, val snippet: String)

    private val RESULT = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val SNIPPET = Regex("""<a[^>]+class="result__snippet"[^>]*>(.*?)</a>|<td class="result-snippet">(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
    private val LITE = Regex("""<a[^>]+rel="nofollow"[^>]+href="([^"]+)"[^>]*class="result-link"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(html: String, max: Int = 6): List<Hit> {
        val links = (RESULT.findAll(html) + LITE.findAll(html)).map { it.groupValues[1] to clean(it.groupValues[2]) }.toList()
        val snippets = SNIPPET.findAll(html).map { clean(it.groupValues[1].ifEmpty { it.groupValues[2] }) }.toList()
        return links.mapIndexed { i, (href, title) -> Hit(title, unwrap(href), snippets.getOrNull(i) ?: "") }
            .filter { it.url.startsWith("http") && it.title.isNotBlank() }
            .distinctBy { it.url }.take(max)
    }

    /** DDG wraps targets as //duckduckgo.com/l/?uddg=<encoded>. */
    fun unwrap(href: String): String {
        val m = Regex("[?&]uddg=([^&]+)").find(href) ?: return if (href.startsWith("//")) "https:$href" else href
        return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
    }

    private fun clean(s: String) = TextExtract.unescape(s.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
}
