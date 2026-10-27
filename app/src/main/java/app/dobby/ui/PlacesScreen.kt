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
import app.dobby.location.GeofenceManager
import app.dobby.location.Place
import app.dobby.location.PlaceRule
import app.dobby.location.PlaceStore
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun PlacesScreen() {
    val ctx = LocalContext.current
    val store = remember { PlaceStore(ctx) }
    val geo = remember { GeofenceManager(ctx) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var addLabel by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var ruleFor by remember { mutableStateOf<Place?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Places", style = MaterialTheme.typography.titleLarge)
        Text("Geofences wake Dobby on enter/exit. Nothing is tracked between events.", style = MaterialTheme.typography.bodySmall)
        if (!geo.hasBackground()) Text("Needs 'Allow location all the time' (Tools → Places & location).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(addLabel, { addLabel = it }, label = { Text("New place label (uses current location)") }, modifier = Modifier.weight(1f))
            TextButton(enabled = addLabel.isNotBlank(), onClick = {
                scope.launch {
                    val loc = geo.currentLocation()
                    if (loc == null) msg = "Could not get location" else {
                        store.upsert(Place(UUID.randomUUID().toString().take(8), addLabel.trim(), loc.first, loc.second)); geo.registerAll(); addLabel = ""; msg = null; tick++
                    }
                }
            }) { Text("Add here") }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        key(tick) {
            LazyColumn {
                items(store.all()) { p ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.label, style = MaterialTheme.typography.titleMedium)
                            Text("%.5f, %.5f · r=%.0fm".format(p.lat, p.lng, p.radiusM), style = MaterialTheme.typography.labelSmall)
                            p.rules.forEach { r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.describe(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { store.upsert(p.copy(rules = p.rules.filter { it.id != r.id })); tick++ }) { Text("x") }
                                }
                            }
                            Row {
                                TextButton(onClick = { ruleFor = p }) { Text("Add rule") }
                                TextButton(onClick = { store.delete(p.id); geo.registerAll(); tick++ }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    ruleFor?.let { p -> AddRuleDialog(p, onDismiss = { ruleFor = null }) { r -> store.upsert(p.copy(rules = p.rules + r)); ruleFor = null; tick++ } }
}

@Composable
private fun AddRuleDialog(p: Place, onDismiss: () -> Unit, onAdd: (PlaceRule) -> Unit) {
    var exit by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf("8") }
    var to by remember { mutableStateOf("11") }
    var title by remember { mutableStateOf("Leaving for office?") }
    var message by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val days = remember { mutableStateListOf(2, 3, 4, 5, 6) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rule for ${p.label}") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("On "); FilterChip(selected = exit, onClick = { exit = true }, label = { Text("exit") }); Spacer(Modifier.width(4.dp)); FilterChip(selected = !exit, onClick = { exit = false }, label = { Text("enter") }) }
                Row { OutlinedTextField(from, { from = it }, label = { Text("From hour") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); OutlinedTextField(to, { to = it }, label = { Text("To hour") }, modifier = Modifier.weight(1f)) }
                Row { for (d in 1..7) FilterChip(selected = d in days, onClick = { if (d in days) days.remove(d) else days.add(d) }, label = { Text(PlaceRule.DAY[d]!!.take(2)) }, modifier = Modifier.padding(end = 2.dp)) }
                OutlinedTextField(title, { title = it }, label = { Text("Notification title") })
                OutlinedTextField(message, { message = it }, label = { Text("Plain message (no model run)") })
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Or a prompt for Dobby to run with tools") }, maxLines = 4)
            }
        },
        confirmButton = {
            TextButton(enabled = from.toIntOrNull() != null && to.toIntOrNull() != null && title.isNotBlank() && (prompt.isNotBlank() || message.isNotBlank()), onClick = {
                onAdd(PlaceRule(UUID.randomUUID().toString().take(8), if (exit) "exit" else "enter", days.toList(), from.toInt(), to.toInt(), title, prompt, message))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
