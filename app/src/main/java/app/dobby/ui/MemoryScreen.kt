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

@Composable
fun MemoryScreen() {
    val app = LocalContext.current.applicationContext as App
    var profile by remember { mutableStateOf(app.memory.profile) }
    var text by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Memory", style = MaterialTheme.typography.titleLarge)
        Text("Profile goes into every prompt. Facts are matched by keyword and added when relevant. All of it stays on the phone.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(profile, { profile = it; app.memory.profile = it }, label = { Text("Profile (a few lines: name, city, accounts' last 4, office)") }, modifier = Modifier.fillMaxWidth(), maxLines = 6)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, label = { Text("New fact") }, modifier = Modifier.weight(2f))
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(tags, { tags = it }, label = { Text("tags") }, modifier = Modifier.weight(1f))
            TextButton(enabled = text.isNotBlank(), onClick = { app.memory.remember(text, tags); text = ""; tags = ""; tick++ }) { Text("Save") }
        }
        Spacer(Modifier.height(8.dp))
        key(tick) {
            LazyColumn {
                items(app.memory.all()) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.text)
                            Text("${f.tags} · ${f.source}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { app.memory.forget(f.id); tick++ }) { Text("Forget") }
                    }
                }
            }
        }
    }
}
