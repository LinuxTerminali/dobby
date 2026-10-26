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

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as App
    var hf by remember { mutableStateOf(app.models.hfToken) }
    var client by remember { mutableStateOf(app.connectors.googleAuth().clientId) }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(hf, { hf = it; app.models.hfToken = it }, label = { Text("Hugging Face token (for gated Gemma models)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(client, { client = it; app.connectors.googleAuth().clientId = it }, label = { Text("Google OAuth client id (Android type)") }, modifier = Modifier.fillMaxWidth())
        Text("Create it in Google Cloud Console → Credentials → OAuth client → Android, package app.dobby, SHA-1 of your signing key. Enable Gmail, Docs and Sheets APIs.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { app.connectors.googleAuth().signOut() }) { Text("Sign out of Google") }
        Spacer(Modifier.height(24.dp))
        Text("Notes", style = MaterialTheme.typography.titleMedium)
        Text("• Sideloaded app: before granting SMS or call-log permission, open App info → ⋮ → Allow restricted settings.\n" +
             "• Everything runs on the phone. Only the Google connectors and the browser tool use the network.\n" +
             "• Models download over Wi-Fi only.", style = MaterialTheme.typography.bodySmall)
    }
}
