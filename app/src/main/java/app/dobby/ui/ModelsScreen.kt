package app.dobby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.dobby.App
import app.dobby.eval.EvalRunner
import app.dobby.model.ModelEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var evalOut by remember { mutableStateOf<String?>(null) }
    var evalBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    key(tick) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
            item {
                if (!app.models.sharedAllowed()) {
                    DobbyCard(highlighted = true) {
                        Text("Keep models across reinstalls", style = MaterialTheme.typography.titleMedium)
                        Hint("Models are in the app's private folder and are deleted on uninstall. Allow all files access to store them in Download/Dobby/models instead.")
                        FilledTonalButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Allow all files access") }
                    }
                } else {
                    val moved = remember { app.models.migrateToShared() }
                    if (moved > 0) Hint("Moved $moved model file(s) to Download/Dobby/models.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("One model is loaded at a time. Gated Gemma files need a Hugging Face token (Settings).", Modifier.weight(1f))
                    TextButton(onClick = { showAdd = true }) { Text("Add by URL") }
                }
                evalOut?.let { Hint(it) }
            }
            items(app.models.all()) { m ->
                ModelCard(m, onChanged = { tick++ }, evalBusy = evalBusy, onEval = {
                    evalBusy = true; evalOut = "Running eval…"
                    scope.launch {
                        evalOut = runCatching { EvalRunner(app).run(m) { p -> evalOut = "Eval $p" } }
                            .map { it.summary + "\nCSV: ${it.csv.name} (adb pull /sdcard/Android/data/app.dobby/files/eval/)" }
                            .getOrElse { "eval failed: ${it.message}" }
                        evalBusy = false
                    }
                })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (showAdd) AddModelDialog(onDismiss = { showAdd = false }, onAdd = { app.models.addCustom(it); showAdd = false; tick++ })
}

@Composable
private fun ModelCard(m: ModelEntry, onChanged: () -> Unit, evalBusy: Boolean, onEval: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val downloaded = app.models.isDownloaded(m)
    val prog = app.downloader.progress(m)
    val selected = app.models.selectedId == m.id
    val running = prog != null && prog.running

    DobbyCard(highlighted = selected) {
        TitleRow(m.name + (if (m.custom) " · custom" else ""),
            "${if (m.sizeGb > 0) "${m.sizeGb} GB · " else ""}${m.backend.uppercase()} · ${if (m.supportsTools) "tools" else "no tools"} · ${if (m.supportsVision) "vision" else "text"} · ${m.license}") {
            if (selected) StatusPill("Active", ok = true)
            else if (downloaded) StatusPill("Downloaded")
            else if (running) StatusPill("${prog!!.status} ${prog.percent}%")
            else if (prog != null) StatusPill(prog.status, ok = false)
            else StatusPill("Not downloaded")
        }
        if (running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { prog!!.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> TextButton(onClick = { app.downloader.cancel(m); onChanged() }) { Text("Cancel") }
                !downloaded -> FilledTonalButton(onClick = { app.downloader.start(m); onChanged() }) { Text("Download") }
                else -> {
                    if (!selected) FilledTonalButton(onClick = { app.models.selectedId = m.id; onChanged() }) { Text("Use") }
                    TextButton(enabled = !evalBusy, onClick = onEval) { Text("Run eval") }
                    TextButton(onClick = { app.downloader.delete(m); if (selected) app.models.selectedId = null; onChanged() }) { Text("Delete") }
                }
            }
            Spacer(Modifier.width(4.dp))
            if (m.custom) TextButton(onClick = { app.models.removeCustom(m.id); onChanged() }) { Text("Remove") }
        }
    }
}

@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onAdd: (ModelEntry) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var gpu by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Add a .litertlm model") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(url, { url = it }, label = { Text("Direct download URL (…/resolve/main/x.litertlm)") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(gpu, { gpu = it }); Text("GPU backend") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(tools, { tools = it }); Text("Supports tools") }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && url.startsWith("http"), onClick = {
                val file = url.substringAfterLast('/').ifBlank { "model.litertlm" }
                onAdd(ModelEntry(id = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-"), name = name, file = file, url = url,
                    sizeGb = 0.0, backend = if (gpu) "gpu" else "cpu", supportsTools = tools, supportsVision = false, license = "", gated = url.contains("huggingface.co"), custom = true))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
