package app.dobby.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LiteRT-LM implementation of LlmEngine. Tools are passed to the runtime as OpenApiTool
 * objects built from MCP ToolSpecs; the runtime runs the tool loop itself
 * (automaticToolCalling = true) and we observe calls through McpOpenApiTool.
 *
 * API reference: https://developers.google.com/edge/litert-lm/android
 */
class LiteRtLmEngine(private val context: Context) : LlmEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var toolObjects: List<McpOpenApiTool> = emptyList()
    private var config: ConversationConfig? = null
    override var loadedModel: ModelEntry? = null
        private set

    override suspend fun load(entry: ModelEntry, modelPath: String, tools: List<ToolSpec>, executor: ToolExecutor, systemPrompt: String) =
        withContext(Dispatchers.IO) {
            close()
            val wantGpu = entry.backend.equals("gpu", ignoreCase = true)
            engine = try {
                open(modelPath, if (wantGpu) Backend.GPU() else Backend.CPU())
            } catch (e: Exception) {
                Log.w(TAG, "backend ${entry.backend} failed, falling back to CPU: ${e.message}")
                if (wantGpu) open(modelPath, Backend.CPU()) else throw e
            }
            toolObjects = if (entry.supportsTools) tools.map { McpOpenApiTool(it, executor) } else emptyList()
            config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                tools = toolObjects.map { tool(it) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            )
            conversation = engine!!.createConversation(config!!)
            loadedModel = entry
        }

    private fun open(modelPath: String, backend: Backend): Engine {
        val cfg = EngineConfig(modelPath = modelPath, backend = backend, cacheDir = context.cacheDir.path)
        val e = Engine(cfg)
        e.initialize()
        return e
    }

    override fun send(userText: String): Flow<ChatEvent> = callbackFlow {
        val conv = conversation ?: run { close(); return@callbackFlow }
        val t0 = System.currentTimeMillis()
        toolObjects.forEach { it.sink = { ev -> trySend(ev) } }
        try {
            conv.sendMessageAsync(userText).collect { item: Any ->
                val t = when (item) {
                    is String -> item
                    else -> item.javaClass.getMethod("getText").invoke(item) as? String ?: item.toString()
                }
                if (t.isNotEmpty()) trySend(ChatEvent.Token(t))
            }
            trySend(ChatEvent.Done(System.currentTimeMillis() - t0))
        } catch (e: Exception) {
            trySend(ChatEvent.Token("\n[error: ${e.message}]"))
            trySend(ChatEvent.Done(System.currentTimeMillis() - t0))
        } finally {
            toolObjects.forEach { it.sink = null }
            close()
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override suspend fun resetConversation() = withContext(Dispatchers.IO) {
        conversation?.close()
        conversation = engine?.createConversation(config!!)
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null; engine = null; loadedModel = null
    }

    companion object { private const val TAG = "LiteRtLmEngine" }
}

/** Bridges one MCP ToolSpec to LiteRT-LM's OpenApiTool. */
class McpOpenApiTool(private val spec: ToolSpec, private val executor: ToolExecutor) : OpenApiTool {
    @Volatile var sink: ((ChatEvent) -> Unit)? = null

    override fun getToolDescriptionJsonString(): String = spec.toOpenApiJson()

    override fun execute(paramsJsonString: String): String {
        sink?.invoke(ChatEvent.ToolCall(spec.name, paramsJsonString))
        val args = runCatching { JSONObject(paramsJsonString) }.getOrElse { JSONObject() }
        val result = try {
            runBlocking { executor.execute(spec.name, args) }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        }
        val s = result.toString()
        sink?.invoke(ChatEvent.ToolResult(spec.name, s.take(600)))
        return s
    }
}
