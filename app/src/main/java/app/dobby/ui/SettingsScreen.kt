package app.dobby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dobby.App
import app.dobby.BuildConfig
import app.dobby.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as App
    var hf by remember { mutableStateOf(app.models.hfToken) }
    var client by remember { mutableStateOf(app.connectors.googleAuth().clientId) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var updateMsg by remember { mutableStateOf("Build ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME})") }
    var downloaded by remember { mutableStateOf(false) }
    LaunchedEffect(release) { while (release != null && !downloaded) { delay(1000); downloaded = UpdateChecker.isDownloaded(ctx, release!!) } }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Updates")
        Text(updateMsg, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = {
                scope.launch {
                    val r = runCatching { UpdateChecker.latest() }.getOrNull()
                    release = r?.takeIf { it.isNewer }
                    updateMsg = when {
                        r == null -> "Could not reach GitHub releases"
                        r.isNewer -> "Build ${r.build} available: ${r.notes.take(80)}"
                        else -> "Up to date (build ${BuildConfig.VERSION_CODE})"
                    }
                }
            }) { Text("Check for updates") }
            release?.let { r ->
                if (!downloaded) TextButton(onClick = { UpdateChecker.download(ctx, r); updateMsg = "Downloading build ${r.build}…" }) { Text("Download") }
                else TextButton(onClick = { UpdateChecker.install(ctx, r) }) { Text("Install") }
            }
        }
        Text("If Play Protect blocks the install, use adb install -r from a computer.", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(16.dp))
        SectionTitle("Tool calling")
        var mode by remember { mutableStateOf(app.models.toolMode) }
        Row {
            listOf("prompt" to "Prompt (app parses TOOL lines)", "native" to "Native (runtime)").forEach { (v, label) ->
                FilterChip(selected = mode == v, onClick = { mode = v; app.models.toolMode = v }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Hint("Prompt mode avoids the runtime's tool path, which corrupted digits in testing. Switch to Native only to compare.")
        Spacer(Modifier.height(16.dp))
        SectionTitle("Accounts")
        OutlinedTextField(hf, { hf = it; app.models.hfToken = it }, label = { Text("Hugging Face token (for gated Gemma models)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(client, { client = it; app.connectors.googleAuth().clientId = it }, label = { Text("Google OAuth client id (Android type)") }, modifier = Modifier.fillMaxWidth())
        Text("Create it in Google Cloud Console → Credentials → OAuth client → Android, package app.dobby, SHA-1 of your signing key. Enable Gmail, Docs and Sheets APIs.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { app.connectors.googleAuth().signOut() }) { Text("Sign out of Google") }
        Spacer(Modifier.height(24.dp))
        SectionTitle("Notes")
        Text("• Sideloaded app: before granting SMS or call-log permission, open App info → ⋮ → Allow restricted settings.\n" +
             "• Everything runs on the phone. Only the Google connectors and the browser tool use the network.\n" +
             "• Models download over Wi-Fi only.", style = MaterialTheme.typography.bodySmall)
    }
}
