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
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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
    override var loadedBackend: String? = null
        private set

    private var promptTools: List<ToolSpec> = emptyList()
    private var promptExecutor: ToolExecutor? = null

    override suspend fun load(entry: ModelEntry, modelPath: String, tools: List<ToolSpec>, executor: ToolExecutor, systemPrompt: String, nativeTools: Boolean) =
        withContext(Dispatchers.IO) {
            close()
            val wantGpu = entry.backend.equals("gpu", ignoreCase = true)
            engine = try {
                open(modelPath, if (wantGpu) Backend.GPU() else Backend.CPU()).also { loadedBackend = if (wantGpu) "GPU" else "CPU" }
            } catch (e: Exception) {
                Log.w(TAG, "backend ${entry.backend} failed, falling back to CPU: ${e.message}")
                if (wantGpu) open(modelPath, Backend.CPU()).also { loadedBackend = "CPU (fallback)" } else throw e
            }
            val useNative = nativeTools && entry.supportsTools
            toolObjects = if (useNative) tools.map { McpOpenApiTool(it, executor) } else emptyList()
            promptTools = if (!useNative) tools else emptyList()
            promptExecutor = executor
            val instruction = if (useNative || tools.isEmpty()) systemPrompt else systemPrompt + "\n\n" + PromptTools.protocol()
            config = ConversationConfig(
                systemInstruction = Contents.of(instruction),
                tools = toolObjects.map { tool(it) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                // Thinking off for speed on a phone; flip on if tool planning suffers.
                thinkingConfig = ThinkingConfig(enableThinking = false),
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

        /** Streams one model turn. Holds back anything that looks like a tool call; returns raw text and how much was shown. */
        suspend fun stream(text: String, holdToolLine: Boolean): Pair<String, Int> {
            val buf = StringBuilder(); var shown = 0
            // Chunks are deltas. Text lives in contents as Content.Text parts; chunks that carry
            // only `channels` (thinking stream) have empty contents and are skipped.
            // `send` (not trySend) so a slow collector applies backpressure instead of dropping chunks.
            conv.sendMessageAsync(text).collect { msg ->
                val t = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (t.isEmpty()) return@collect
                buf.append(t)
                val visible = if (holdToolLine) PromptTools.visiblePrefix(buf.toString(), final = false) else buf.toString()
                if (visible.length > shown) { send(ChatEvent.Token(visible.substring(shown))); shown = visible.length }
            }
            return buf.toString() to shown
        }

        try {
            if (promptTools.isEmpty()) {
                try {
                    stream(userText, holdToolLine = false)
                } catch (e: Exception) {
                    // Native tool path: the model emitted a call the runtime could not parse; one clean retry.
                    if (e.message?.contains("parse tool call", ignoreCase = true) == true) {
                        Log.w(TAG, "malformed tool call, retrying once: ${e.message}")
                        send(ChatEvent.ToolResult("runtime", "malformed tool call; retrying"))
                        stream("Your last tool call was malformed. Call the tool again with all string arguments in double quotes, or answer without a tool.", false)
                    } else throw e
                }
            } else {
                // Prompt-based tool loop, capped at MAX_STEPS calls.
                val offered = ToolRouter.route(userText, promptTools)
                send(ChatEvent.ToolResult("router", "offered ${offered.size}/${promptTools.size}: " + offered.joinToString(", ") { it.name }))
                var next = PromptTools.turnMessage(userText, offered)
                var steps = 0
                var corrected = false
                while (true) {
                    val (raw, shown) = stream(next, holdToolLine = true)
                    val call = PromptTools.parse(raw, promptTools)   // accept any known tool, not only the offered ones
                    if (call == null) {
                        if (PromptTools.looksLikeCall(raw) && !corrected) {
                            // Looked like a tool call but did not parse: one correction, then give up gracefully.
                            corrected = true
                            send(ChatEvent.ToolResult("runtime", "unparsed tool call; asking for a retry: " + raw.take(120)))
                            next = PromptTools.CORRECTION
                            continue
                        }
                        // Plain answer (or unfixable): flush whatever the hold-back kept.
                        if (raw.length > shown) send(ChatEvent.Token(raw.substring(shown)))
                        break
                    }
                    if (steps >= PromptTools.MAX_STEPS) { send(ChatEvent.Token("\n[stopped after ${PromptTools.MAX_STEPS} tool calls]")); break }
                    steps++
                    send(ChatEvent.ToolCall(call.name, call.args.toString()))
                    val result = try { promptExecutor!!.execute(call.name, call.args) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.toString()) }
                    send(ChatEvent.ToolResult(call.name, result.toString().take(600)))
                    next = PromptTools.resultMessage(call.name, result)
                }
            }
            send(ChatEvent.Done(System.currentTimeMillis() - t0))
        } catch (e: Exception) {
            send(ChatEvent.Token("\n[error: ${e.message}]"))
            send(ChatEvent.Done(System.currentTimeMillis() - t0))
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
        conversation = null; engine = null; loadedModel = null; loadedBackend = null
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
