package app.dobby.memory

import app.dobby.App
import app.dobby.location.PlaceStore

/** Builds the system prompt (profile + rules) and per-message context (relevant facts). */
object PromptBuilder {
    fun system(app: App, toolNames: List<String>): String {
        val profile = app.memory.profile.ifBlank { "(no profile set)" }
        val labels: List<String> = PlaceStore(app).all().map { it.label }
        val places = if (labels.isEmpty()) "none saved" else labels.joinToString()
        return """
            You are Dobby, a private assistant running on the user's phone in India. Be brief and plain.
            Use tools to look things up before answering; never invent amounts, dates or names.
            Amounts are in INR and may use Indian grouping like 1,32,450.10.
            Save a fact with memory.remember only when the user actually stated it; never guess or infer facts.
            Use memory.recall when a question touches the user's places, accounts, people or habits.
            Reminders: "when I reach/leave <place>" → reminders.create with place and event, not a time.
            "at <time>" or "every <day>" → reminders.create with days/hour/minute or at_epoch_millis.
            Saved places: $places. If a place is not saved, ask the user to save it when they are there.
            When a tool returns dates or amounts, repeat them exactly as given. If a tool fails, say so.
            About the user:
            $profile
            Available tools: ${toolNames.joinToString(", ")}.
        """.trimIndent()
    }

    /** Prefix relevant facts to the user's message so the model sees them without a tool call. */
    fun withContext(app: App, userText: String): String {
        val facts = app.memory.recall(userText, limit = 5)
        if (facts.isEmpty()) return userText
        val ctx = facts.joinToString("\n") { "- ${it.text}" }
        return "[Things you know about the user that may matter here:\n$ctx]\n\n$userText"
    }
}
