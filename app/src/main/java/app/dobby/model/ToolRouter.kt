package app.dobby.model

import app.dobby.mcp.ToolSpec

/**
 * Picks the tools to offer for one turn from keyword rules. Deterministic and cheap; the full
 * set is the fallback when nothing matches. Fewer tools = shorter prefill and fewer wrong picks.
 */
object ToolRouter {
    data class Group(val name: String, val keywords: List<String>, val tools: List<String>)

    val GROUPS = listOf(
        Group("calls", listOf("call", "called", "calls", "missed", "rang", "dial", "talk to", "talked"),
            listOf("calls.recent", "calls.missed", "calls.with")),
        Group("sms", listOf("sms", "message", "messages", "text me", "texted", "bill", "debited", "credited", "upi", "bank", "balance", "spent", "spend", "paid", "due", "otp"),
            listOf("sms.search", "sms.recent", "gmail.search")),
        Group("gmail", listOf("email", "emails", "mail", "gmail", "inbox"),
            listOf("gmail.search", "gmail.read")),
        Group("docs", listOf("doc", "docs", "document", "sheet", "sheets", "spreadsheet", "google"),
            listOf("docs.read", "sheets.read")),
        Group("web", listOf("http", "www", "website", "web", "fetch", "summarise", "summarize", "page", "url", "wiki", "search", "look up", "lookup", "research", "status", "latest", "news", "what is", "who is", "how to"),
            listOf("web.search", "web.fetch", "web.extract", "web.research")),
        Group("device", listOf("date", "time", "today", "now", "where am i", "location", "my current"),
            listOf("device.now", "device.location")),
        Group("reminders", listOf("remind", "reminder", "reminders", "alarm", "wake", "every", "schedule"),
            listOf("reminders.create", "reminders.list", "reminders.delete", "places.list", "device.now")),
        Group("places", listOf("place", "places", "office", "home", "reach", "arrive", "leave", "left", "geofence", "save this", "current location", "set it as", "set as my"),
            listOf("places.list", "places.set", "places.add_rule", "places.events", "places.delete", "device.location")),
        Group("memory", listOf("remember", "forget", "recall", "know about", "note that", "profile", "what do you know"),
            listOf("memory.remember", "memory.recall", "memory.forget", "memory.profile")),
    )

    /** Always offered when any group matches, so facts can be looked up in any context. */
    private val ALWAYS = listOf("memory.recall")

    fun route(text: String, all: List<ToolSpec>): List<ToolSpec> {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ") + " "
        val wanted = GROUPS.filter { g -> g.keywords.any { k -> t.contains(" $k ") || (k.contains(' ') && t.contains(k)) } }
            .flatMap { it.tools }.toSet()
        if (wanted.isEmpty()) return all
        val names = wanted + ALWAYS
        return all.filter { it.name in names }
    }

    fun groupsFor(text: String): List<String> {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ") + " "
        return GROUPS.filter { g -> g.keywords.any { k -> t.contains(" $k ") || (k.contains(' ') && t.contains(k)) } }.map { it.name }
    }
}
