package app.dobby.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextExtractTest {
    private val html = """<html><head><script>x()</script><style>.a{}</style></head><body>
        <nav>Home | Trains | Login</nav><header>RailYatri</header>
        <div><h1>16589 Rani Chennamma Express</h1><p>Running status: on time at Tumkur, arrived 23:56 departed 23:58.</p>
        <table><tr><td>Davangere</td><td>Mostly Delayed</td><td>3:20 to 3:22</td></tr></table>
        <p>Belagavi arrival 8:50 &amp; departure 8:55 platform #1</p></div>
        <footer>&copy; 2026</footer></body></html>"""

    @Test fun mainTextDropsBoilerplate() {
        val t = TextExtract.mainText(html)
        assertFalse(t, t.contains("Login") || t.contains("x()") || t.contains("2026"))
        assertTrue(t, t.contains("16589 Rani Chennamma Express") && t.contains("Davangere") && t.contains("8:50 & departure 8:55"))
    }

    @Test fun passagesFindRelevantWindows() {
        val t = TextExtract.mainText(html)
        val p = TextExtract.passages(t, "Davangere delayed", window = 80, max = 2)
        assertTrue(p.toString(), p.isNotEmpty() && p[0].text.contains("Davangere") && p[0].score == 2)
        val none = TextExtract.passages(t, "zzzz", window = 40)
        assertEquals(1, none.size); assertEquals(0, none[0].score)
    }

    @Test fun searchParseHandlesDdgHtml() {
        val ddg = """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.railyatri.in%2Flive-train-status%2F16589&amp;rut=abc">16589 Live Status - RailYatri</a>
            <a class="result__snippet" href="x">Rani <b>Chennamma</b> Express running status</a></div>
            <div class="result"><a class="result__a" href="https://erail.in/16589">eRail 16589</a><a class="result__snippet">Timetable</a></div>"""
        val hits = SearchParse.parse(ddg)
        assertEquals(2, hits.size)
        assertEquals("https://www.railyatri.in/live-train-status/16589", hits[0].url)
        assertEquals("16589 Live Status - RailYatri", hits[0].title)
        assertEquals("Rani Chennamma Express running status", hits[0].snippet)
        assertEquals("https://erail.in/16589", hits[1].url)
    }
}
