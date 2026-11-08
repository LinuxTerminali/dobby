package app.dobby.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Any OpenAI-compatible server: vLLM, llama.cpp server, Ollama, LM Studio, a hosted API.
 * Talks to {baseUrl}/chat/completions with streaming SSE. Keeps the conversation history itself.
 * Pure JVM, so it is unit-testable; the only Android-specific part is where the key is stored.
 */
class RemoteBackend(
    private val baseUrl: String,          // e.g. http://100.64.0.5:8000/v1
    private val model: String,            // model id the server expects
    private val apiKey: String = "",
    private val temperature: Double = 0.0,
    private val connectMs: Int = 10_000,
    private val readMs: Int = 120_000,
) : ChatBackend {
    override val label = "remote"
    private val history = mutableListOf<JSONObject>()
    private var system = ""

    override suspend fun open(systemPrompt: String) { system = systemPrompt; history.clear() }

    override fun stream(text: String): Flow<String> = flow {
        history += msg("user", text)
        val reply = StringBuilder()
        try {
            request(messages = listOf(msg("system", system)) + history, stream = true) { delta -> reply.append(delta); emit(delta) }
        } finally {
            if (reply.isNotEmpty()) history += msg("assistant", reply.toString())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun oneShot(prompt: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        request(messages = listOf(msg("user", prompt)), stream = false) { out.append(it) }
        out.toString().take(maxChars)
    }

    override suspend fun reset() { history.clear() }
    override suspend fun close() { history.clear() }
    override fun isStateError(message: String) = message.contains("context length", ignoreCase = true) || message.contains("maximum context", ignoreCase = true)

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    /** One HTTP call. With stream=true, SSE `data:` lines are parsed and deltas handed to `onDelta`. */
    private suspend fun request(messages: List<JSONObject>, stream: Boolean, onDelta: suspend (String) -> Unit) {
        val body = JSONObject().put("model", model).put("messages", JSONArray(messages)).put("temperature", temperature).put("stream", stream)
        val c = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.connectTimeout = connectMs; c.readTimeout = readMs
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("HTTP $code from $baseUrl: ${err.take(300)}")
        }
        c.inputStream.bufferedReader().use { r ->
            if (stream) readSse(r, onDelta)
            else {
                val j = JSONObject(r.readText())
                val content = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                if (content.isNotEmpty()) onDelta(content)
            }
        }
    }

    private suspend fun readSse(r: BufferedReader, onDelta: suspend (String) -> Unit) {
        while (true) {
            val line = r.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val delta = parseDelta(data) ?: continue
            if (delta.isNotEmpty()) onDelta(delta)
        }
    }

    companion object {
        /** `choices[0].delta.content` from one SSE JSON payload; null if the payload is not a chunk. */
        fun parseDelta(json: String): String? = runCatching {
            val j = JSONObject(json)
            val choice = j.optJSONArray("choices")?.optJSONObject(0) ?: return null
            choice.optJSONObject("delta")?.optString("content", "") ?: choice.optJSONObject("message")?.optString("content", "") ?: ""
        }.getOrNull()
    }
}
