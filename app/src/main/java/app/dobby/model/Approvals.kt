package app.dobby.model

import app.dobby.mcp.Effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission gate for tool calls, modelled on "ask before side effects, remember allow rules".
 * READ and WRITE run; DESTRUCTIVE waits for the user unless the tool is always-allowed,
 * the run is an eval (autoApprove), or there is no user to ask (headless → deny).
 */
object Approvals {
    enum class Decision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

    @Volatile var autoApprove = false   // eval runs
    @Volatile var headless = false      // scheduled jobs: nobody to ask
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    private var seq = 0L

    /** Pure decision for the no-prompt cases; null means "ask the user". */
    fun preDecide(effect: Effect, alwaysAllowed: Boolean): Decision? = when {
        effect != Effect.DESTRUCTIVE -> Decision.ALLOW_ONCE
        alwaysAllowed || autoApprove -> Decision.ALLOW_ONCE
        headless -> Decision.DENY
        else -> null
    }

    /** Called by the engine loop. `emit` shows the request in the UI; then we wait for [resolve]. */
    suspend fun request(name: String, args: JSONObject, emit: suspend (id: String) -> Unit, timeoutMs: Long = 120_000): Decision {
        val id = "a${++seq}"
        val d = CompletableDeferred<Decision>()
        pending[id] = d
        emit(id)
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: Decision.DENY
        pending.remove(id)
        return r
    }

    fun resolve(id: String, decision: Decision) { pending.remove(id)?.complete(decision) }
}
