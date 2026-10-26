package app.dobby.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dobby.reminders.Reminder
import app.dobby.reminders.ReminderScheduler
import app.dobby.reminders.ReminderStore
import java.util.UUID

@Composable
fun RemindersScreen() {
    val ctx = LocalContext.current
    val store = remember { ReminderStore(ctx) }
    val scheduler = remember { ReminderScheduler(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reminders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) { Text("Add") }
        }
        Text("Alarm-clock style: the phone wakes only at the set time. A reminder with a prompt also runs the model with your tools and shows the answer.", style = MaterialTheme.typography.bodySmall)
        if (!exactOk) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }) { Text("Allow exact alarms (otherwise ±10 min)") }
        Spacer(Modifier.height(8.dp))
        key(tick) {
            LazyColumn {
                items(store.all()) { r ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleMedium)
                                Text(r.describe(), style = MaterialTheme.typography.bodySmall)
                                if (r.prompt.isNotBlank()) Text("runs: ${r.prompt}", style = MaterialTheme.typography.labelSmall)
                                r.nextTrigger()?.let { Text("next: ${java.util.Date(it)}", style = MaterialTheme.typography.labelSmall) }
                            }
                            Switch(checked = r.enabled, onCheckedChange = { on -> val u = r.copy(enabled = on); store.upsert(u); scheduler.schedule(u); tick++ })
                            TextButton(onClick = { scheduler.cancel(r.id); store.delete(r.id); tick++ }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddReminderDialog(onDismiss = { showAdd = false }) { r -> store.upsert(r); scheduler.schedule(r); showAdd = false; tick++ }
}

@Composable
private fun AddReminderDialog(onDismiss: () -> Unit, onAdd: (Reminder) -> Unit) {
    var title by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }
    var prompt by remember { mutableStateOf("") }
    val days = remember { mutableStateListOf<Int>() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New recurring reminder") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                Row { OutlinedTextField(hour, { hour = it }, label = { Text("Hour 0-23") }, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); OutlinedTextField(minute, { minute = it }, label = { Text("Min") }, modifier = Modifier.weight(1f)) }
                Row { for (d in 1..7) FilterChip(selected = d in days, onClick = { if (d in days) days.remove(d) else days.add(d) }, label = { Text(Reminder.DAY[d]!!.take(2)) }, modifier = Modifier.padding(end = 2.dp)) }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt for Dobby (optional)") }, placeholder = { Text("e.g. Any bills due this week? Check SMS.") })
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && days.isNotEmpty() && hour.toIntOrNull() in 0..23 && minute.toIntOrNull() in 0..59, onClick = {
                onAdd(Reminder(UUID.randomUUID().toString().take(8), title, "", hour.toInt(), minute.toInt(), days.toList(), 0, prompt))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
