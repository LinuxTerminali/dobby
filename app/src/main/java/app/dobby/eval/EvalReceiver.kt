package app.dobby.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dobby.App
import app.dobby.reminders.ReminderReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lets scripts/eval.sh start an eval over adb:
 *   adb shell am broadcast -a app.dobby.EVAL -n app.dobby/.eval.EvalReceiver --es model gemma4-e4b
 * Results land in <external files>/eval/ (adb pull). Exported for the shell user; guarded by the action only.
 */
class EvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.dobby.EVAL") return
        val app = context.applicationContext as App
        val id = intent.getStringExtra("model") ?: app.models.selectedId
        val entry = app.models.all().firstOrNull { it.id == id } ?: return
        intent.getStringExtra("backend")?.let { app.models.setBackendOverride(entry.id, it.lowercase()) }
        intent.getStringExtra("tools")?.let { app.models.toolMode = it.lowercase() }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val run = EvalRunner(app).run(entry)
                File(app.getExternalFilesDir(null), "eval/latest.txt").writeText(run.summary)
                ReminderReceiver.notify(app, 7001, "Eval done", run.summary.lineSequence().first())
            } catch (e: Exception) {
                File(app.getExternalFilesDir(null), "eval/latest.txt").writeText("ERROR ${e.message}")
            } finally { pending.finish() }
        }
    }
}
