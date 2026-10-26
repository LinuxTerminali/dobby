package app.dobby.model

import android.content.Context

/** One engine in memory at a time. Swap runtime here when adding llama.cpp. */
object EngineHolder {
    @Volatile private var engine: LlmEngine? = null
    fun get(context: Context): LlmEngine = engine ?: LiteRtLmEngine(context.applicationContext).also { engine = it }
}
