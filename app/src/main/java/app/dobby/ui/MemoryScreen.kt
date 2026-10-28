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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    key(tick) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
            item {
                Hint("Everything here stays on the phone. The profile goes into every prompt; facts are added when they match what you ask.")
                SectionTitle("Profile")
                OutlinedTextField(profile, { profile = it; app.memory.profile = it },
                    placeholder = { Text("Name, city, accounts' last 4, office, anything Dobby should always know") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                SectionTitle("Add a fact", Modifier.padding(top = 16.dp))
                OutlinedTextField(text, { text = it }, placeholder = { Text("Office map pin is wrong; use the north gate") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(tags, { tags = it }, placeholder = { Text("tags: office, cab") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = text.isNotBlank(), onClick = { app.memory.remember(text, tags); text = ""; tags = ""; tick++ }) { Text("Save") }
                }
                SectionTitle("Facts", Modifier.padding(top = 16.dp))
            }
            val facts = app.memory.all()
            if (facts.isEmpty()) item { Hint("No facts yet. Tell Dobby things in chat, or add one above.") }
            items(facts) { f ->
                ListItem(
                    headlineContent = { Text(f.text) },
                    supportingContent = { Text(listOf(f.tags, f.source).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall) },
                    trailingContent = { IconButton(onClick = { app.memory.forget(f.id); tick++ }) { Icon(Icons.Filled.Delete, contentDescription = "Forget") } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.padding(vertical = 3.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
