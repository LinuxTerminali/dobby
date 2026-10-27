package app.dobby.chat

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dobby.App
import app.dobby.memory.PromptBuilder
import app.dobby.model.ChatEvent
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
    private var loadedSignature: String? = null
    private var nextId = 1L

    /** Reload when the selected model or the active tool set changed. */
    private suspend fun ensureLoaded(): Boolean {
        val entry = app.models.all().firstOrNull { it.id == app.models.selectedId }?.let { app.models.withBackend(it) }
        if (entry == null) { status.value = "Pick a model in the Models tab"; return false }
        if (!app.models.isDownloaded(entry)) { status.value = "Model not downloaded: ${entry.name}"; return false }
        val tools = app.router.listTools()
        val sig = entry.id + "|" + entry.backend + "|" + tools.joinToString(",") { it.name }
        if (sig == loadedSignature && EngineHolder.get(app).loadedModel != null) return true
        status.value = "Loading ${entry.name}…"
        val executor = ToolExecutor { name, args -> app.router.callTool(name, args) }
        try {
            EngineHolder.get(app).load(entry, app.models.fileFor(entry).absolutePath, tools, executor, PromptBuilder.system(app, tools.map { it.name }))
        } catch (e: Exception) {
            status.value = "Load failed: ${e.message}"; return false
        }
        loadedSignature = sig
        status.value = "${entry.name} · ${EngineHolder.get(app).loadedBackend} · ${tools.size} tools"
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
            EngineHolder.get(app).send(PromptBuilder.withContext(app, text)).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> { pending.append(ev.text); if (System.currentTimeMillis() - lastFlush > 80) flush() }
                    is ChatEvent.ToolCall -> { flush(); update(replyId) { it.copy(tools = it.tools + "→ ${ev.name} ${ev.args.take(160)}") } }
                    is ChatEvent.ToolResult -> { update(replyId) { it.copy(tools = it.tools + "← ${ev.name}: ${ev.result.take(160)}") } }
                    is ChatEvent.Done -> { flush(); status.value = status.value.substringBefore(" · took") + " · took ${ev.millis / 1000.0}s" }
                }
            }
            flush()
            update(replyId) { it.copy(streaming = false) }
            busy.value = false
        }
    }

    fun clear() { messages.clear(); viewModelScope.launch { runCatching { EngineHolder.get(app).resetConversation() } } }

    fun unload() { viewModelScope.launch { EngineHolder.get(app).close(); loadedSignature = null; status.value = "No model loaded" } }
}
