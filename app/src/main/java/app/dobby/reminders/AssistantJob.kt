package app.dobby.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dobby.App
import app.dobby.model.Approvals
import app.dobby.model.ChatEvent
import app.dobby.model.EngineHolder
import app.dobby.model.ToolExecutor
import app.dobby.memory.PromptBuilder

/**
 * Runs a reminder's prompt through the model with the active tools, posts the answer,
 * then unloads the model. This is how a scheduled check (bills due, missed calls) works
 * without anything staying resident.
 */
class AssistantJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val title = inputData.getString("title") ?: "Dobby"
        val prompt = inputData.getString("prompt") ?: return Result.failure()
        val notifId = inputData.getInt("notif_id", title.hashCode())
        val entry = app.models.all().firstOrNull { it.id == app.models.selectedId } ?: return fail(app, notifId, title, "no model selected")
        if (!app.models.isDownloaded(entry)) return fail(app, notifId, title, "model not downloaded")
        val engine = EngineHolder.get(app)
        Approvals.headless = true
        return try {
            val tools = app.router.listTools()
            engine.load(entry, app.models.fileFor(entry).absolutePath, tools,
                ToolExecutor { n, a -> app.router.callTool(n, a) },
                PromptBuilder.system(app, tools.map { it.name }), nativeTools = app.models.toolMode == "native", isAlwaysAllowed = { app.permissions.isAlwaysAllowed(it) })
            val out = StringBuilder()
            engine.send(PromptBuilder.withContext(app, prompt)).collect { if (it is ChatEvent.Token) out.append(it.text) }
            ReminderReceiver.notify(app, notifId, title, out.toString().ifBlank { "(no answer)" }.take(1500))
            Result.success()
        } catch (e: Exception) {
            fail(app, notifId, title, e.message ?: e.toString())
        } finally {
            Approvals.headless = false
            runCatching { engine.close() }
        }
    }

    private fun fail(app: App, notifId: Int, title: String, why: String): Result {
        ReminderReceiver.notify(app, notifId, title, "Could not run check: $why")
        return Result.failure()
    }
}
