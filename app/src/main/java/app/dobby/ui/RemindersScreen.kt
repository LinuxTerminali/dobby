package app.dobby.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dobby.reminders.Reminder
import app.dobby.reminders.ReminderScheduler
import app.dobby.reminders.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val NEXT_FMT = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US)

@Composable
fun RemindersScreen() {
    val ctx = LocalContext.current
    val store = remember { ReminderStore(ctx) }
    val scheduler = remember { ReminderScheduler(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    key(tick) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("The phone wakes only at the set time. A reminder with a prompt also runs the model and shows the answer.", Modifier.weight(1f))
                    TextButton(onClick = { showAdd = true }) { Text("Add") }
                }
                if (!exactOk) FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }) { Text("Allow exact alarms") }
            }
            val all = store.all()
            if (all.isEmpty()) item { Hint("No reminders. Tell Dobby in chat, e.g. “remind me every weekday at 8 to check bills”.") }
            items(all) { r ->
                DobbyCard {
                    TitleRow(r.title, r.describe() + (r.nextTrigger()?.let { " · next " + NEXT_FMT.format(Date(it)) } ?: "")) {
                        Switch(checked = r.enabled, onCheckedChange = { on -> val u = r.copy(enabled = on); store.upsert(u); scheduler.schedule(u); tick++ })
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { scheduler.cancel(r.id); store.delete(r.id); tick++ }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                    if (r.prompt.isNotBlank()) Hint("Runs: ${r.prompt}", Modifier.padding(top = 6.dp))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
