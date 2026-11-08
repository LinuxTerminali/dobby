package app.dobby.model

import android.content.Context
import android.util.Log
import app.dobby.App

/** One engine, one loaded model. The backend is chosen per ModelEntry.runtime. */
object EngineHolder {
    @Volatile private var engine: AgentEngine? = null

    fun get(context: Context): LlmEngine {
        val a = context.applicationContext as App
        return engine ?: AgentEngine { entry ->
            when (entry.runtime) {
                "remote" -> RemoteBackend(entry.endpoint, entry.modelName, a.models.apiKey(entry.id))
                else -> LiteRtLmBackend(a, a.models.fileFor(entry).absolutePath, entry.backend.equals("gpu", ignoreCase = true))
            }
        }.also { it.log = { m -> Log.w("AgentEngine", m) }; engine = it }
    }
}
