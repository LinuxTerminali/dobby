package app.dobby.util

/** Turns a user query into SMS search terms, with synonyms for bank vocabulary. Pure Kotlin. */
object SmsQuery {
    private val SYNONYMS = mapOf(
        "bill" to listOf("due", "payable", "amt due", "bill"),
        "bills" to listOf("due", "payable", "amt due", "bill"),
        "due" to listOf("due", "payable"),
        "balance" to listOf("avl bal", "available balance", "balance", "bal"),
        "spent" to listOf("spent", "debited", "paid"),
        "credited" to listOf("credited", "received"),
    )
    /** Query words → search terms, with synonyms for the money vocabulary banks actually use. */
    fun expand(query: String): List<String> {
        val words = query.lowercase().split(Regex("[^a-z0-9@.+-]+")).filter { it.length >= 2 && it !in STOP }
        if (words.isEmpty()) return listOf(query.trim()).filter { it.isNotEmpty() }
        return words.flatMap { SYNONYMS[it] ?: listOf(it) }.distinct().take(8)
    }
    private val STOP = setOf("the", "my", "me", "for", "from", "any", "this", "that", "week", "today", "find", "sms", "message", "messages", "about", "and", "of", "to", "in", "on", "is", "are")
}
