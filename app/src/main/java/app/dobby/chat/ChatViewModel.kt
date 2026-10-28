package app.dobby.chat

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dobby.App
import app.dobby.memory.PromptBuilder
import app.dobby.model.Approvals
import app.dobby.model.ChatEvent
import app.dobby.model.Compactor
import app.dobby.model.EngineHolder
import app.dobby.model.ToolExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Immutable: every update replaces the list entry so Compose sees a change. */
data class ChatMessage(val id: Long, val role: String, val text: String, val tools: List<String> = emptyList(), val streaming: Boolean = false)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val app get() = getApplication<App>()
    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("No model loaded")
    val busy = mutableStateOf(false)
    /** A destructive tool call waiting for the user: (approval id, tool, args). */
    val pendingApproval = mutableStateOf<Triple<String, String, String>?>(null)
    private var loadedSignature: String? = null
    /** Summary of compacted earlier turns; prefixed to the next message once. */
    private var pendingContext: String? = null
    private var turnsSinceCompaction = 0
    val compactions = mutableStateOf(0)
    private var nextId = 1L

    /** Reload when the selected model or the active tool set changed. */
    private suspend fun ensureLoaded(): Boolean {
        val entry = app.models.all().firstOrNull { it.id == app.models.selectedId }?.let { app.models.withBackend(it) }
        if (entry == null) { status.value = "Pick a model in the Models tab"; return false }
        if (!app.models.isDownloaded(entry)) { status.value = "Model not downloaded: ${entry.name}"; return false }
        val tools = app.router.listTools()
        val sig = entry.id + "|" + entry.backend + "|" + app.models.toolMode + "|" + tools.joinToString(",") { it.name }
        if (sig == loadedSignature && EngineHolder.get(app).loadedModel != null) return true
        status.value = "Loading ${entry.name}…"
        val executor = ToolExecutor { name, args -> app.router.callTool(name, args) }
        try {
            EngineHolder.get(app).load(entry, app.models.fileFor(entry).absolutePath, tools, executor, PromptBuilder.system(app, tools.map { it.name }),
                nativeTools = app.models.toolMode == "native", isAlwaysAllowed = { app.permissions.isAlwaysAllowed(it) })
        } catch (e: Exception) {
            status.value = "Load failed: ${e.message}"; return false
        }
        loadedSignature = sig
        status.value = "${entry.name} · ${EngineHolder.get(app).loadedBackend} · ${tools.size} tools (${app.models.toolMode})"
        return true
    }

    private fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    fun send(text: String) {
        if (text.isBlank() || busy.value) return
        messages += ChatMessage(nextId++, "user", text)
        val replyId = nextId++
        messages += ChatMessage(replyId, "model", "", streaming = true)
        busy.value = true
        viewModelScope.launch {
            if (!ensureLoaded()) { update(replyId) { it.copy(text = status.value, streaming = false) }; busy.value = false; return@launch }
            // Batch tokens: flush to the UI at most every 80 ms to avoid a recomposition per chunk.
            val pending = StringBuilder()
            var lastFlush = System.currentTimeMillis()
            fun flush() {
                if (pending.isEmpty()) return
                val chunk = pending.toString(); pending.setLength(0)
                update(replyId) { it.copy(text = it.text + chunk) }
                lastFlush = System.currentTimeMillis()
            }
            val prefix = pendingContext?.let { Compactor.contextPrefix(it) } ?: ""
            pendingContext = null
            EngineHolder.get(app).send(prefix + PromptBuilder.withContext(app, text)).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> { pending.append(ev.text); if (System.currentTimeMillis() - lastFlush > 80) flush() }
                    is ChatEvent.ToolCall -> { flush(); update(replyId) { it.copy(tools = it.tools + "→ ${ev.name} ${ev.args.take(160)}") } }
                    is ChatEvent.ToolResult -> { pendingApproval.value = null; update(replyId) { it.copy(tools = it.tools + "← ${ev.name}: ${ev.result.take(160)}") } }
                    is ChatEvent.Confirm -> { flush(); pendingApproval.value = Triple(ev.id, ev.name, ev.args) }
                    is ChatEvent.Done -> { flush(); status.value = status.value.substringBefore(" · took") + " · took ${ev.millis / 1000.0}s" }
                }
            }
            flush()
            update(replyId) { it.copy(streaming = false) }
            turnsSinceCompaction++
            maybeCompact()
            busy.value = false
        }
    }

    /** Past the budget: summarise older turns, save lasting facts, restart the conversation with the note. */
    private suspend fun maybeCompact() {
        val engine = EngineHolder.get(app)
        if (!Compactor.shouldCompact(engine.contextChars, turnsSinceCompaction)) return
        status.value = status.value.substringBefore(" · took") + " · compacting…"
        val turns = messages.filter { it.role == "user" || it.role == "model" }.map { Compactor.Turn(it.role, it.text) }
        val (older, keep) = Compactor.split(turns)
        if (older.isEmpty()) return
        val out = runCatching { engine.oneShot(Compactor.prompt(older, lastSummary)) }.getOrDefault("")
        val r = Compactor.parse(out)
        r.facts.forEach { (text, tags) -> app.memory.remember(text, tags, "compaction") }
        lastSummary = r.summary
        pendingContext = r.summary + if (keep.isNotEmpty()) "\nMost recent exchange: " + keep.joinToString(" / ") { "${it.role}: ${it.text.take(200)}" } else ""
        runCatching { engine.resetConversation() }
        turnsSinceCompaction = 0
        compactions.value++
        messages += ChatMessage(nextId++, "system", "Earlier turns summarised" + if (r.facts.isNotEmpty()) "; ${r.facts.size} fact(s) saved to memory" else "")
        status.value = status.value.substringBefore(" · compacting")
    }
    private var lastSummary: String? = null

    fun approve(always: Boolean) {
        val (id, tool, _) = pendingApproval.value ?: return
        if (always) app.permissions.setAlwaysAllowed(tool, true)
        Approvals.resolve(id, if (always) Approvals.Decision.ALLOW_ALWAYS else Approvals.Decision.ALLOW_ONCE)
        pendingApproval.value = null
    }

    fun deny() {
        val (id, _, _) = pendingApproval.value ?: return
        Approvals.resolve(id, Approvals.Decision.DENY)
        pendingApproval.value = null
    }

    fun clear() { messages.clear(); pendingContext = null; lastSummary = null; turnsSinceCompaction = 0; viewModelScope.launch { runCatching { EngineHolder.get(app).resetConversation() } } }

    fun unload() { viewModelScope.launch { EngineHolder.get(app).close(); loadedSignature = null; status.value = "No model loaded" } }
}
