package app.dobby.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * On-device backend over LiteRT-LM. Plain chat only: the runtime's native tool path corrupted
 * output on the Pixel 9 and is not used; tools are handled by AgentEngine above this.
 * API reference: https://developers.google.com/edge/litert-lm/android
 */
class LiteRtLmBackend(private val context: Context, private val modelPath: String, private val wantGpu: Boolean) : ChatBackend {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var config: ConversationConfig? = null
    private var labelState = if (wantGpu) "GPU" else "CPU"
    override val label: String get() = labelState

    override suspend fun open(systemPrompt: String) = withContext(Dispatchers.IO) {
        if (engine == null) {
            engine = try {
                start(if (wantGpu) Backend.GPU() else Backend.CPU())
            } catch (e: Exception) {
                Log.w(TAG, "backend $labelState failed, falling back to CPU: ${e.message}")
                if (wantGpu) start(Backend.CPU()).also { labelState = "CPU (fallback)" } else throw e
            }
        }
        conversation?.close()
        config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            thinkingConfig = ThinkingConfig(enableThinking = false),   // off for speed on a phone
        )
        conversation = engine!!.createConversation(config!!)
    }

    private fun start(backend: Backend): Engine {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = context.cacheDir.path))
        e.initialize()
        return e
    }

    /** Chunks are deltas; text lives in Content.Text parts. Chunks carrying only thinking channels are skipped. */
    override fun stream(text: String): Flow<String> = flow {
        val conv = conversation ?: throw IllegalStateException("no conversation")
        conv.sendMessageAsync(text).collect { msg ->
            val t = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            if (t.isNotEmpty()) emit(t)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun oneShot(prompt: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val e = engine ?: return@withContext ""
        val tmp = e.createConversation(ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            thinkingConfig = ThinkingConfig(enableThinking = false)))
        try {
            val out = StringBuilder()
            tmp.sendMessageAsync(prompt).collect { msg -> out.append(msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }) }
            out.toString().take(maxChars)
        } finally { runCatching { tmp.close() } }
    }

    override suspend fun reset() = withContext(Dispatchers.IO) { conversation?.close(); conversation = null; Unit }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { conversation?.close() }; runCatching { engine?.close() }
        conversation = null; engine = null
    }

    /** Status 9 (prefill work group / state entries) and 13 (task failed): the conversation outgrew the backend. */
    override fun isStateError(message: String) =
        message.contains("state entries") || message.contains("Status Code: 9") || message.contains("Status Code: 13") || message.contains("Task failed")

    companion object { private const val TAG = "LiteRtLmBackend" }
}
