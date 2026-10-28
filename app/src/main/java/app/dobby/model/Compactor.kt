package app.dobby.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Context compaction: when a conversation grows past a budget, older turns are summarised into a
 * short note, durable facts are extracted for memory, and the conversation restarts with the note.
 * Pure Kotlin; the engine call happens in the view model.
 */
object Compactor {
    /** ~6K tokens of transcript. Keeps prefill and KV memory bounded on a phone. */
    const val BUDGET_CHARS = 24_000
    const val MIN_TURNS = 6
    const val KEEP_RECENT = 2   // last user/assistant pairs are replayed verbatim after compaction

    data class Turn(val role: String, val text: String)
    data class Result(val summary: String, val facts: List<Pair<String, String>>)  // (text, tags)

    fun shouldCompact(contextChars: Long, turns: Int) = turns >= MIN_TURNS && contextChars > BUDGET_CHARS

    /** Turns to summarise vs turns to keep verbatim. */
    fun split(turns: List<Turn>): Pair<List<Turn>, List<Turn>> {
        val keep = turns.takeLast(KEEP_RECENT * 2)
        return turns.dropLast(keep.size) to keep
    }

    fun prompt(older: List<Turn>, previousSummary: String?): String {
        val transcript = older.joinToString("\n") { "${if (it.role == "user") "User" else "Dobby"}: ${it.text.take(600)}" }
        return """
            Summarise this conversation for your own future reference. Output exactly this format:
            SUMMARY: <at most 120 words: what the user asked, what was found, what is still open>
            FACTS: <JSON array of lasting facts the user stated about themselves, each {"text":"...","tags":"a,b"}; [] if none>
            Do not include passing events (what was spent or done today). Do not invent anything.
            ${previousSummary?.let { "Earlier summary: $it\n" } ?: ""}
            Conversation:
            $transcript
        """.trimIndent()
    }

    fun parse(output: String): Result {
        val summary = Regex("SUMMARY:\\s*(.*?)(?:\\n\\s*FACTS:|$)", RegexOption.DOT_MATCHES_ALL).find(output)?.groupValues?.get(1)?.trim().orEmpty()
        val factsRaw = Regex("FACTS:\\s*(\\[.*\\])", RegexOption.DOT_MATCHES_ALL).find(output)?.groupValues?.get(1)
        val facts = mutableListOf<Pair<String, String>>()
        if (factsRaw != null) runCatching {
            val arr = JSONArray(factsRaw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text").trim()
                if (t.length in 8..200) facts += t to o.optString("tags", "")
            }
        }
        return Result(summary.ifBlank { output.take(500) }, facts)
    }

    /** What the next user message is prefixed with after compaction. */
    fun contextPrefix(summary: String) = "[Earlier in this conversation: $summary]\n\n"
}
