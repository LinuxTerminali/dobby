package app.dobby.memory

import app.dobby.App

/** Builds the system prompt (profile + rules) and per-message context (relevant facts). */
object PromptBuilder {
    fun system(app: App, toolNames: List<String>): String {
        val profile = app.memory.profile.ifBlank { "(no profile set)" }
        return """
            You are Dobby, a private assistant running on the user's phone in India. Be brief and plain.
            Use tools to look things up before answering; never invent amounts, dates or names.
            Amounts are in INR and may use Indian grouping like 1,32,450.10.
            When the user tells you something worth keeping (a place, an account, a preference, a quirk),
            call memory.remember with short text and tags. Use memory.recall when a question touches the user's
            places, accounts, people or habits. If a tool fails, say so.
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
