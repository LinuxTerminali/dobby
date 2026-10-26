package app.dobby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }   // refresh download progress

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Models", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) { Text("Add by URL") }
        }
        Text("Download, select, and compare. Only one model is loaded at a time.", style = MaterialTheme.typography.bodySmall)
        evalOut?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        key(tick) {
            LazyColumn {
                items(app.models.all()) { m -> ModelRow(m, onChanged = { tick++ },
                    onEval = {
                        evalBusy = true; evalOut = "Running eval…"
                        scope.launch { evalOut = runCatching { EvalRunner(app).run(m) }.getOrElse { "eval failed: ${it.message}" }; evalBusy = false }
                    }, evalBusy = evalBusy) }
            }
        }
    }
    if (showAdd) AddModelDialog(onDismiss = { showAdd = false }, onAdd = { app.models.addCustom(it); showAdd = false; tick++ })
}

@Composable
private fun ModelRow(m: ModelEntry, onChanged: () -> Unit, onEval: () -> Unit, evalBusy: Boolean) {
    val app = LocalContext.current.applicationContext as App
    val downloaded = app.models.isDownloaded(m)
    val prog = app.models.let { app.downloader.progress(m) }
    val selected = app.models.selectedId == m.id
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(m.name + (if (m.custom) " (custom)" else ""), style = MaterialTheme.typography.titleMedium)
            Text("${m.sizeGb} GB · ${m.backend} · tools:${if (m.supportsTools) "yes" else "no"} · vision:${if (m.supportsVision) "yes" else "no"} · ${m.license}${if (m.gated) " · gated (HF token)" else ""}",
                style = MaterialTheme.typography.labelSmall)
            val state = when {
                downloaded -> "downloaded (${app.downloader.sizeOnDisk(m) / 1_000_000} MB)"
                prog != null && prog.running -> "${prog.status} ${prog.percent}%"
                prog != null -> prog.status
                else -> "not downloaded"
            }
            Text(state, style = MaterialTheme.typography.bodySmall)
            if (prog != null && prog.running) LinearProgressIndicator(progress = { prog.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Row {
                if (!downloaded && (prog == null || !prog.running)) TextButton(onClick = { app.downloader.start(m); onChanged() }) { Text("Download") }
                if (prog != null && prog.running) TextButton(onClick = { app.downloader.cancel(m); onChanged() }) { Text("Cancel") }
                if (downloaded) {
                    TextButton(enabled = !selected, onClick = { app.models.selectedId = m.id; onChanged() }) { Text(if (selected) "Selected" else "Select") }
                    TextButton(enabled = !evalBusy, onClick = onEval) { Text("Run eval") }
                    TextButton(onClick = { app.downloader.delete(m); if (selected) app.models.selectedId = null; onChanged() }) { Text("Delete") }
                }
                if (m.custom) TextButton(onClick = { app.models.removeCustom(m.id); onChanged() }) { Text("Remove") }
            }
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
