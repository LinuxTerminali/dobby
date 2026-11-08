package app.dobby.model

import app.dobby.mcp.Effect
import app.dobby.mcp.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * The harness: prompt-based tool loop with routing, validation, approvals, retries and context
 * accounting, over any [ChatBackend]. Pure Kotlin, unit-tested with a fake backend.
 */
class AgentEngine(private val backends: (ModelEntry) -> ChatBackend) : LlmEngine {
    private var backend: ChatBackend? = null
    private var tools: List<ToolSpec> = emptyList()
    private var executor: ToolExecutor? = null
    private var alwaysAllowed: (String) -> Boolean = { false }
    private var systemPrompt = ""
    override var loadedModel: ModelEntry? = null; private set
    override val loadedBackend: String? get() = backend?.label
    override var contextChars: Long = 0L; private set
    var log: (String) -> Unit = {}

    override suspend fun load(entry: ModelEntry, modelPath: String, tools: List<ToolSpec>, executor: ToolExecutor, systemPrompt: String,
                              isAlwaysAllowed: (String) -> Boolean) {
        close()
        this.tools = tools; this.executor = executor; this.alwaysAllowed = isAlwaysAllowed
        this.systemPrompt = if (tools.isEmpty()) systemPrompt else systemPrompt + "\n\n" + PromptTools.protocol()
        Capacity.current = Capacity.forEntry(entry)
        val b = backends(entry)
        b.open(this.systemPrompt)
        backend = b; loadedModel = entry; contextChars = 0
    }

    override fun send(userText: String): Flow<ChatEvent> = channelFlow {
        val b = backend ?: run { send(ChatEvent.Token("[no model loaded]")); send(ChatEvent.Done(0)); return@channelFlow }
        val t0 = System.currentTimeMillis()

        /** One model turn; holds back anything that looks like a tool call. Returns raw text and chars already shown. */
        suspend fun stream(text: String, hold: Boolean): Pair<String, Int> {
            val buf = StringBuilder(); var shown = 0
            contextChars += text.length
            b.stream(text).collect { t ->
                if (t.isEmpty()) return@collect
                contextChars += t.length
                buf.append(t)
                val visible = if (hold) PromptTools.visiblePrefix(buf.toString(), final = false) else buf.toString()
                if (visible.length > shown) { send(ChatEvent.Token(visible.substring(shown))); shown = visible.length }
            }
            return buf.toString() to shown
        }

        try {
            if (tools.isEmpty()) {
                stream(userText, hold = false)
            } else {
                val offered = ToolRouter.route(userText, tools)
                send(ChatEvent.ToolResult("router", "offered ${offered.size}/${tools.size}: " + offered.joinToString(", ") { it.name }))
                var next = PromptTools.turnMessage(userText, offered)
                val cap = Capacity.current
                var steps = 0; var corrected = false; var replyRetried = false
                val sources = mutableListOf(userText)
                val cal = Calendar.getInstance()
                val todayNums = setOf(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)).map { it.toString() }.toSet()
                while (true) {
                    val (raw, shown) = stream(next, hold = true)
                    val call = PromptTools.parse(raw, tools)   // any known tool, not only the offered ones
                    if (call == null) {
                        if (PromptTools.looksLikeCall(raw) && !corrected) {
                            corrected = true
                            send(ChatEvent.ToolResult("runtime", "unparsed tool call; asking for a retry: " + raw.take(120)))
                            next = PromptTools.CORRECTION
                            continue
                        }
                        val issue = Validator.checkReply(raw, sources, todayNums)
                        if (issue != null && !replyRetried) {
                            replyRetried = true
                            send(ChatEvent.Retract("numbers not in results: " + issue.ungrounded.joinToString(", ")))
                            next = Validator.replyCorrection(issue)
                            continue
                        }
                        if (raw.length > shown) send(ChatEvent.Token(raw.substring(shown)))
                        if (issue != null) send(ChatEvent.Token("\n" + Validator.marker(issue)))
                        break
                    }
                    val argIssues = Validator.checkArgs(userText, call.args)
                    if (argIssues.isNotEmpty() && steps < cap.maxSteps) {
                        steps++
                        send(ChatEvent.ToolResult("validator", "blocked ${call.name}: " + argIssues.joinToString { "${it.key}=${it.got} (user said ${it.expected})" }))
                        next = Validator.argCorrection(argIssues)
                        continue
                    }
                    if (steps >= cap.maxSteps) { send(ChatEvent.Token("\n[stopped after ${cap.maxSteps} tool calls]")); break }
                    steps++
                    send(ChatEvent.ToolCall(call.name, call.args.toString()))
                    val effect = tools.firstOrNull { it.name == call.name }?.effect ?: Effect.READ
                    val decision = Approvals.preDecide(effect, alwaysAllowed(call.name))
                        ?: Approvals.request(call.name, call.args, emit = { id -> send(ChatEvent.Confirm(id, call.name, call.args.toString())) })
                    val result = if (decision == Approvals.Decision.DENY) JSONObject().put("error", "The user did not approve this action. Do not retry it; tell the user it was not done.")
                        else try { executor!!.execute(call.name, call.args) } catch (e: Exception) { JSONObject().put("error", e.message ?: e.toString()) }
                    send(ChatEvent.ToolResult(call.name, result.toString().take(600)))
                    sources += result.toString()
                    next = PromptTools.resultMessage(call.name, result, cap.toolDataChars)
                    if (isEmptyResult(result)) next += "\n(The result was empty. If another listed tool could answer this, call it; otherwise tell the user.)"
                }
            }
            send(ChatEvent.Done(System.currentTimeMillis() - t0))
        } catch (e: Exception) {
            val m = e.message ?: e.toString()
            log("send failed: $m")
            if (b.isStateError(m)) send(ChatEvent.RuntimeError(m)) else send(ChatEvent.Token("\n[error: $m]"))
            send(ChatEvent.Done(System.currentTimeMillis() - t0))
        }
    }

    override suspend fun oneShot(prompt: String, maxChars: Int): String = backend?.oneShot(prompt, maxChars) ?: ""

    override suspend fun resetConversation() { backend?.reset(); backend?.open(systemPrompt); contextChars = 0 }

    override suspend fun close() { runCatching { backend?.close() }; backend = null; loadedModel = null }

    companion object {
        fun isEmptyResult(r: JSONObject): Boolean =
            r.optInt("count", -1) == 0 || r.optString("summary").startsWith("No ", ignoreCase = true) || r.has("error")
    }
}
