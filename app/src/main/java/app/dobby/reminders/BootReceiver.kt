package app.dobby.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are lost on reboot; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler(context).rescheduleAll(); app.dobby.location.GeofenceManager(context).registerAll()
    }
}
