package app.dobby.model

import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** Tool executor the engine calls back into (routes to connectors). */
fun interface ToolExecutor { suspend fun execute(name: String, args: JSONObject): JSONObject }

/** Event stream from one reply. */
sealed class ChatEvent {
    data class Token(val text: String) : ChatEvent()
    data class ToolCall(val name: String, val args: String) : ChatEvent()
    data class ToolResult(val name: String, val result: String) : ChatEvent()
    data class Done(val millis: Long) : ChatEvent()
}

/**
 * The one interface the rest of the app talks to. Any runtime (LiteRT-LM today,
 * llama.cpp later) implements this, so models and runtimes can be swapped freely.
 */
interface LlmEngine {
    val loadedModel: ModelEntry?
    /** "GPU" or "CPU": what actually initialised (may differ from the request after a fallback). */
    val loadedBackend: String?
    suspend fun load(entry: ModelEntry, modelPath: String, tools: List<ToolSpec>, executor: ToolExecutor, systemPrompt: String, nativeTools: Boolean = false)
    fun send(userText: String): Flow<ChatEvent>
    suspend fun resetConversation()
    suspend fun close()
}
