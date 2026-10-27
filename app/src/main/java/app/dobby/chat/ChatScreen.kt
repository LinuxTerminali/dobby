package app.dobby.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SUGGESTIONS = listOf(
    "Did I miss any calls this week?",
    "Any bills due this week? Check SMS",
    "What's the date today?",
    "Remind me to drink water at 3 PM",
)

@Composable
fun ChatScreen(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (vm.busy.value) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (vm.messages.isEmpty()) {
            EmptyState(Modifier.weight(1f)) { vm.send(it) }
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.messages, key = { it.id }) { m -> MessageRow(m) }
            }
        }
        vm.pendingApproval.value?.let { (_, tool, args) -> ApprovalCard(tool, args, onAllow = { vm.approve(false) }, onAlways = { vm.approve(true) }, onDeny = { vm.deny() }) }
        InputBar(input, onChange = { input = it }, enabled = !vm.busy.value) { vm.send(input); input = "" }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: (String) -> Unit) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Hi, I'm Dobby.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Everything stays on this phone. Ask about your calls, messages, bills, or set a reminder.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        SUGGESTIONS.forEach { s ->
            SuggestionChip(onClick = { onPick(s) }, label = { Text(s) }, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
                modifier = Modifier.widthIn(max = 300.dp)) {
                Text(m.text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("d", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (m.tools.isNotEmpty()) ToolTrace(m.tools)
                    if (m.text.isEmpty() && m.streaming) {
                        CircularProgressIndicator(Modifier.size(18.dp).padding(top = 4.dp), strokeWidth = 2.dp)
                    } else {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** Collapsed line "Used calls.with" that expands to the raw call/result lines. */
@Composable
private fun ToolTrace(lines: List<String>) {
    var open by remember { mutableStateOf(false) }
    val names = lines.filter { it.startsWith("→") }.map { it.removePrefix("→ ").substringBefore(' ') }.distinct()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 8.dp).clickable { open = !open }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (names.isEmpty()) "Working…" else "Used " + names.joinToString(", "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 6.dp)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(tool: String, args: String, onAllow: () -> Unit, onAlways: () -> Unit, onDeny: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("Dobby wants to run $tool", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(args.take(200), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 6.dp))
            Row {
                androidx.compose.material3.FilledTonalButton(onClick = onAllow) { Text("Allow") }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onAlways) { Text("Always allow") }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = value, onValueChange = onChange, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Dobby…") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
