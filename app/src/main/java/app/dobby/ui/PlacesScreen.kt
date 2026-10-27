package app.dobby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    key(tick) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
            item {
                Hint("Geofences wake Dobby on enter or exit. Nothing is tracked in between.")
                if (!geo.hasBackground()) Hint("Needs location “Allow all the time” (Tools → Places & location).", error = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(addLabel, { addLabel = it }, placeholder = { Text("New place, e.g. office") }, modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = addLabel.isNotBlank(), onClick = {
                        scope.launch {
                            val loc = geo.currentLocation()
                            if (loc == null) msg = "Could not get a location fix" else {
                                store.upsert(Place(UUID.randomUUID().toString().take(8), addLabel.trim(), loc.first, loc.second)); geo.registerAll(); addLabel = ""; msg = null; tick++
                            }
                        }
                    }) { Text("Add here") }
                }
                msg?.let { Hint(it, error = true) }
                Spacer(Modifier.height(8.dp))
            }
            val all = store.all()
            if (all.isEmpty()) item { Hint("No places yet. Stand where you want and tap “Add here”, or tell Dobby “save this place as office”.") }
            items(all) { p ->
                DobbyCard {
                    TitleRow(p.label, "%.5f, %.5f · %.0f m".format(p.lat, p.lng, p.radiusM)) {
                        IconButton(onClick = { store.delete(p.id); geo.registerAll(); tick++ }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                    p.rules.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(r.describe(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { store.upsert(p.copy(rules = p.rules.filter { it.id != r.id })); tick++ }) { Icon(Icons.Filled.Close, contentDescription = "Remove rule") }
                        }
                    }
                    TextButton(onClick = { ruleFor = p }) { Text("Add rule") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
