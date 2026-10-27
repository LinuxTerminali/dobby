package app.dobby.eval

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import app.dobby.App
import app.dobby.memory.PromptBuilder
import app.dobby.model.ChatEvent
import app.dobby.model.EngineHolder
import app.dobby.model.ModelEntry
import app.dobby.model.ToolExecutor
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs assets/golden.json against a model with ALL connectors' tools offered and every tool
 * call answered from the case's `mocks` (deterministic; no permissions or network needed).
 * Writes a CSV + summary under <external files>/eval/ and returns the summary text.
 */
class EvalRunner(private val app: App) {

    data class Run(val summary: String, val csv: File, val results: List<EvalMetrics.CaseResult>)

    suspend fun run(requested: ModelEntry, onProgress: (String) -> Unit = {}): Run {
        val entry = app.models.withBackend(requested)
        val golden = JSONObject(app.assets.open("golden.json").bufferedReader().readText())
        val cases = golden.getJSONArray("cases")
        val tools = app.connectors.all.flatMap { s -> s.tools().map { t -> t.copy(name = "${s.id}.${t.name}") } }
        val offered = tools.map { it.name }.toSet()
        val engine = EngineHolder.get(app)
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeStart = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        var currentMocks = JSONObject()
        var calls = mutableListOf<Pair<String, JSONObject>>()
        val executor = ToolExecutor { name, args ->
            calls += name to args
            currentMocks.optJSONObject(name) ?: JSONObject().put("error", "no mock for $name in this case")
        }
        val t0 = System.currentTimeMillis()
        engine.load(entry, app.models.fileFor(entry).absolutePath, tools, executor, PromptBuilder.system(app, tools.map { it.name }))
        val loadMs = System.currentTimeMillis() - t0

        val results = mutableListOf<EvalMetrics.CaseResult>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            onProgress("${i + 1}/${cases.length()} ${c.getString("id")}")
            engine.resetConversation()
            currentMocks = c.optJSONObject("mocks") ?: JSONObject()
            calls = mutableListOf()
            val out = StringBuilder(); var ttft = -1L; var total = 0L
            val start = System.currentTimeMillis()
            engine.send(c.getString("prompt")).collect { ev ->
                when (ev) {
                    is ChatEvent.Token -> { if (ttft < 0) ttft = System.currentTimeMillis() - start; out.append(ev.text) }
                    is ChatEvent.Done -> total = ev.millis
                    else -> {}
                }
            }
            val mockTexts = currentMocks.keys().asSequence().map { currentMocks.get(it).toString() }.toList()
            results += EvalMetrics.score(c, calls, offered, out.toString(), if (ttft < 0) total else ttft, total, mockTexts)
        }
        val chargeEnd = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val pssMb = Debug.getPss() / 1024

        val dir = File(app.getExternalFilesDir(null), "eval").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val tag = "${entry.id}-${entry.backend}-$stamp"
        val csv = File(dir, "$tag.csv")
        csv.writeText(EvalMetrics.csvHeader() + "\n" + results.joinToString("\n") { EvalMetrics.csvRow(it) } + "\n")
        val summary = EvalMetrics.summary(results, entry.name) +
            "\nbackend ${engine.loadedBackend} · load ${loadMs} ms · pss ${pssMb} MB · battery ${(chargeStart - chargeEnd) / 1000} mAh · ${app.models.fileFor(entry).name}"
        val txt = File(dir, "$tag.txt")
        txt.writeText(summary + "\n\n" + results.filter { !it.pass }.joinToString("\n") {
            "FAIL ${it.id}: calls=${it.calls} bad=${it.badNumbers} reply=${it.reply.take(160).replace('\n', ' ')}" })
        exportToDownloads(listOf(csv, txt))
        return Run(summary, csv, results)
    }

    /** Copy results to Download/Dobby/eval so a file manager (or Termux) can read them. Needs all-files access. */
    private fun exportToDownloads(files: List<File>) {
        if (!android.os.Environment.isExternalStorageManager()) return
        val out = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dobby/eval").apply { mkdirs() }
        files.forEach { f -> runCatching { f.copyTo(File(out, f.name), overwrite = true) } }
    }
}
