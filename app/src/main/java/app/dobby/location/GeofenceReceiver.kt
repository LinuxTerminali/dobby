package app.dobby.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.dobby.reminders.AssistantJob
import app.dobby.reminders.ReminderReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.util.Calendar

/** Enter/exit event: log it, then run any rule whose window matches. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val type = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            else -> return
        }
        val store = PlaceStore(context)
        val now = Calendar.getInstance()
        val dow = now.get(Calendar.DAY_OF_WEEK); val hour = now.get(Calendar.HOUR_OF_DAY)
        for (g in ev.triggeringGeofences ?: emptyList()) {
            val place = store.get(g.requestId) ?: continue
            store.addEvent(LocationEvent(place.id, place.label, type, System.currentTimeMillis()))
            place.rules.filter { it.event == type && (it.days.isEmpty() || dow in it.days) && hour >= it.fromHour && hour < it.toHour }
                .forEach { rule ->
                    ReminderReceiver.notify(context, rule.id.hashCode(), rule.title, "Checking…")
                    WorkManager.getInstance(context).enqueue(
                        OneTimeWorkRequestBuilder<AssistantJob>()
                            .setInputData(workDataOf("title" to rule.title, "prompt" to rule.prompt, "notif_id" to rule.id.hashCode())).build())
                }
        }
    }
}
