package app.dobby.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.dobby.App
import app.dobby.connectors.Connector
import app.dobby.connectors.Requirement
import kotlinx.coroutines.launch

private fun iconFor(id: String): ImageVector = when (id) {
    "calls" -> Icons.Filled.Call; "sms" -> Icons.Filled.Sms; "gmail" -> Icons.Filled.Email
    "docs" -> Icons.Filled.Description; "sheets" -> Icons.Filled.TableChart; "web" -> Icons.Filled.Language
    "device" -> Icons.Filled.PhoneAndroid; "reminders" -> Icons.Filled.Alarm; "places" -> Icons.Filled.Place
    "memory" -> Icons.Filled.Memory; else -> Icons.Filled.Extension
}

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

    key(tick) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
            item {
                Hint("Each connector is a set of tools the model can call. Switch one on and complete its setup.")
                error?.let { Hint(it, error = true) }
            }
            items(app.connectors.all) { c ->
                ConnectorCard(c,
                    onPermissions = { permLauncher.launch(it.toTypedArray()) },
                    onGoogle = { scopes ->
                        try { authLauncher.launch(app.connectors.googleAuth().signInIntent(scopes)) }
                        catch (e: Exception) { error = e.message }
                    },
                    onChanged = { tick++ })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectorCard(c: Connector, onPermissions: (List<String>) -> Unit, onGoogle: (List<String>) -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val ready = c.isReady(ctx)
    var enabled by remember(c.id) { mutableStateOf(app.connectors.state.isEnabled(c.id)) }
    val restricted = c.id == "sms" || c.id == "calls"

    DobbyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(c.id), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; app.connectors.state.setEnabled(c.id, it); onChanged() })
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(when { !enabled -> "Off"; ready -> "Ready"; else -> "Needs setup" }, ok = if (!enabled) null else ready)
            Spacer(Modifier.width(8.dp))
            Text(c.tools().joinToString(" · ") { it.name }, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        when (val r = c.requirement) {
            is Requirement.Permissions -> if (!ready) {
                val bg = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                val fg = r.permissions.filter { it != bg }
                val fgOk = fg.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
                Spacer(Modifier.height(8.dp))
                if (!fgOk) {
                    if (restricted) {
                        Hint("Sideloaded app: open App info → ⋮ → Allow restricted settings first, then grant.")
                        Row {
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Open App info") }
                            FilledTonalButton(onClick = { onPermissions(fg) }) { Text("Grant permission") }
                        }
                    } else FilledTonalButton(onClick = { onPermissions(fg) }) { Text("Grant permission") }
                } else if (bg in r.permissions) {
                    Hint("Geofences need location access all the time.")
                    FilledTonalButton(onClick = { onPermissions(listOf(bg)) }) { Text("Allow all the time") }
                }
            } else if (c.id == "device" && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                TextButton(onClick = { onPermissions(r.permissions) }) { Text("Grant location (optional)") }
            }
            is Requirement.GoogleOAuth -> if (!ready) {
                Spacer(Modifier.height(8.dp))
                if (app.connectors.googleAuth().clientId.isBlank()) Hint("Set the Google OAuth client id in Settings first.")
                FilledTonalButton(onClick = { onGoogle(r.scopes) }) { Text("Sign in with Google") }
            }
            Requirement.None -> {}
        }
    }
}
