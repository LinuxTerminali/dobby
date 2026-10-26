package app.dobby.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.dobby.connectors.Connector
import app.dobby.connectors.Requirement
import kotlinx.coroutines.launch

@Composable
fun ConnectorsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        scope.launch {
            try { if (res.resultCode == Activity.RESULT_OK || res.data != null) app.connectors.googleAuth().handleRedirect(res.data) }
            catch (e: Exception) { error = "Google sign-in failed: ${e.message}" }
            tick++
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Connectors", style = MaterialTheme.typography.titleLarge)
        Text("Each connector is an MCP server. Switched on + ready = visible to the model.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        key(tick) {
            LazyColumn {
                items(app.connectors.all) { c -> ConnectorRow(c, onPermissions = { permLauncher.launch(it.toTypedArray()) },
                    onGoogle = { scopes ->
                        try { authLauncher.launch(app.connectors.googleAuth().signInIntent(scopes)) }
                        catch (e: Exception) { error = e.message }
                    }, onToggle = { tick++ }) }
            }
        }
    }
}

@Composable
private fun ConnectorRow(c: Connector, onPermissions: (List<String>) -> Unit, onGoogle: (List<String>) -> Unit, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val ready = c.isReady(ctx)
    var enabled by remember(c.id) { mutableStateOf(app.connectors.state.isEnabled(c.id)) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                    Text(c.description, style = MaterialTheme.typography.bodySmall)
                    Text("tools: " + c.tools().joinToString { "${c.id}.${it.name}" }, style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; app.connectors.state.setEnabled(c.id, it); onToggle() })
            }
            when (val r = c.requirement) {
                is Requirement.Permissions -> if (!ready) {
                    val bg = android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    val fg = r.permissions.filter { it != bg }
                    val fgOk = fg.all { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (!fgOk) TextButton(onClick = { onPermissions(fg) }) { Text("Grant permission") }
                    else if (bg in r.permissions) TextButton(onClick = { onPermissions(listOf(bg)) }) { Text("Allow location all the time") }
                }
                    else if (c.id == "device") TextButton(onClick = { onPermissions(r.permissions) }) { Text("Grant location (optional)") }
                is Requirement.GoogleOAuth -> if (!ready) TextButton(onClick = { onGoogle(r.scopes) }) { Text("Sign in with Google") }
                Requirement.None -> {}
            }
            Text(if (ready) "ready" else "not ready", style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
