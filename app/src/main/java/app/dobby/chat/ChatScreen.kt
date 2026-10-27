package app.dobby.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size) { if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.lastIndex) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vm.status.value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.clear() }) { Text("Clear") }
            TextButton(onClick = { vm.unload() }) { Text("Unload") }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f)) {
            items(vm.messages, key = { it.id }) { m ->
                val user = m.role == "user"
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(10.dp)) {
                            m.tools.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                            if (m.tools.isNotEmpty()) Spacer(Modifier.height(4.dp))
                            Text(if (m.text.isEmpty() && m.streaming) "…" else m.text)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask…") }, maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button(enabled = !vm.busy.value, onClick = { vm.send(input); input = "" }) { Text("Send") }
        }
    }
}
