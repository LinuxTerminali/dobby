package app.dobby.util

/**
 * Readability-style extraction and passage search, pure Kotlin.
 * The point: a 4B model should see the relevant 1–2K chars of a page, not the first 1.5K.
 */
object TextExtract {

    /** Visible text from HTML with navigation/boilerplate removed, paragraphs on their own lines. */
    fun mainText(html: String): String {
        var h = html
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "iframe"))
            h = h.replace(Regex("(?is)<$tag\\b[^>]*>.*?</$tag>"), " ")
        h = h.replace(Regex("(?is)<!--.*?-->"), " ")
        h = h.replace(Regex("(?i)</t[dh]>\\s*<t[dh][^>]*>"), " | ")   // cell | cell
        h = h.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>|</td>|</th>|</section>|</article>"), "\n")
        h = h.replace(Regex("<[^>]+>"), " ")
        h = unescape(h)
        return h.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .filter { it.length > 1 }
            .joinToString("\n")
            .replace(Regex("\\n{2,}"), "\n")
    }

    fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " " }

    data class Passage(val text: String, val score: Int, val offset: Int)

    /**
     * Windows of `window` chars around the best matches for the query words, ranked by distinct
     * query words hit. Returns at most `max` non-overlapping passages, in document order.
     */
    fun passages(text: String, query: String, window: Int = 500, max: Int = 4): List<Passage> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty() || text.isEmpty()) return listOf(Passage(text.take(window), 0, 0))
        val low = text.lowercase()
        val hits = mutableListOf<Int>()
        for (w in words) { var i = low.indexOf(w); while (i >= 0 && hits.size < 2000) { hits += i; i = low.indexOf(w, i + 1) } }
        if (hits.isEmpty()) return listOf(Passage(text.take(window), 0, 0))
        val candidates = hits.sorted().map { h ->
            val start = (h - window / 3).coerceAtLeast(0)
            val end = (start + window).coerceAtMost(text.length)
            val chunk = low.substring(start, end)
            Passage(text.substring(start, end).trim(), words.count { chunk.contains(it) }, start)
        }.sortedByDescending { it.score }
        val chosen = mutableListOf<Passage>()
        for (c in candidates) {
            if (chosen.size >= max) break
            if (chosen.none { kotlin.math.abs(it.offset - c.offset) < window / 2 }) chosen += c
        }
        return chosen.sortedBy { it.offset }
    }

    fun joinPassages(ps: List<Passage>, sep: String = "\n…\n") = ps.joinToString(sep) { it.text }

    data class Link(val text: String, val url: String)
    private val A = Regex("""<a\s[^>]*href\s*=\s*["']([^"'#]+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Absolute http(s) links with their anchor text, de-duplicated, same-page anchors and media skipped. */
    fun links(html: String, baseUrl: String): List<Link> {
        val base = runCatching { java.net.URL(baseUrl) }.getOrNull() ?: return emptyList()
        return A.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1].trim(); val text = unescape(m.groupValues[2].replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
            if (href.startsWith("javascript:") || href.startsWith("mailto:") || text.length < 3) return@mapNotNull null
            val abs = runCatching { java.net.URL(base, href).toString() }.getOrNull() ?: return@mapNotNull null
            if (!abs.startsWith("http") || Regex("\\.(png|jpe?g|gif|svg|css|js|pdf|zip)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(abs)) return@mapNotNull null
            Link(text.take(120), abs)
        }.distinctBy { it.url }.toList()
    }

    /** Links whose text or url mention the query words, best first. */
    fun relevantLinks(links: List<Link>, query: String, max: Int = 3): List<Link> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return emptyList()
        return links.map { l -> l to words.count { w -> l.text.lowercase().contains(w) || l.url.lowercase().contains(w) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** Query variants for research: the question, its content words, and (if long) the first clause. */
    fun queryVariants(question: String): List<String> {
        val stop = setOf("what","is","the","a","an","of","for","in","on","to","how","does","do","are","was","were","and","or","my","me","i","it","this","that","when","which","who")
        val words = question.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }
        val out = mutableListOf(question.trim())
        if (words.size >= 2) out += words.take(8).joinToString(" ")
        question.split(Regex("[?,;]")).firstOrNull()?.trim()?.takeIf { it.length in 12 until question.length - 4 }?.let { out += it }
        return out.distinct()
    }
}
