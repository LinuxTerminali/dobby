package app.dobby.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Fires at the alarm time: posts the notification, queues a model run if the reminder has a prompt, reschedules. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val store = ReminderStore(context)
        val r = store.get(id) ?: return
        notify(context, r.id.hashCode(), r.title, r.message.ifBlank { if (r.prompt.isNotBlank()) "Checking…" else "Reminder" })
        if (r.prompt.isNotBlank()) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<AssistantJob>().setInputData(workDataOf("title" to r.title, "prompt" to r.prompt, "notif_id" to r.id.hashCode())).build())
        }
        if (r.recurring) ReminderScheduler(context).schedule(r) else store.upsert(r.copy(enabled = false))
    }

    companion object {
        const val CHANNEL = "reminders"
        fun notify(context: Context, notifId: Int, title: String, text: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(notifId, n) }
        }
    }
}
