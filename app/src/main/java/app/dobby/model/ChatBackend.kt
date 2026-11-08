package app.dobby.model

import kotlinx.coroutines.flow.Flow

/**
 * The only runtime-specific surface. Everything above it (tool loop, router, validator,
 * approvals, compaction, eval) is shared. Implementations: LiteRtLmBackend (on-device),
 * RemoteBackend (any OpenAI-compatible /chat/completions endpoint).
 */
interface ChatBackend {
    /** Human label for status lines, e.g. "GPU", "CPU (fallback)", "remote". */
    val label: String
    /** Start a conversation with this system prompt (called once per load, and after reset). */
    suspend fun open(systemPrompt: String)
    /** Stream one assistant turn for `text` as deltas. Throws on runtime failure. */
    fun stream(text: String): Flow<String>
    /** Single tool-less call outside the conversation (compaction summaries). */
    suspend fun oneShot(prompt: String, maxChars: Int): String
    /** Forget the conversation, keep the model loaded. */
    suspend fun reset()
    suspend fun close()
    /** True for errors that mean "this conversation is too big for the backend". */
    fun isStateError(message: String): Boolean = false
}
