package app.dobby.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * One AlarmManager alarm per reminder. The phone wakes only at the trigger time;
 * nothing runs in between, so idle battery cost is zero. Exact alarms are used when
 * allowed (USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM), otherwise a window alarm.
 */
class ReminderScheduler(private val context: Context) {
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ReminderStore(context)

    fun schedule(r: Reminder) {
        cancel(r.id)
        if (!r.enabled) return
        val at = r.nextTrigger() ?: return
        val pi = pending(r.id)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) {
            // setAlarmClock: exact, survives Doze, shows in the status bar like a clock alarm.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openAppIntent()), pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
        }
    }

    fun cancel(id: String) { am.cancel(pending(id)) }

    fun rescheduleAll() { store.all().forEach { schedule(it) } }

    private fun pending(id: String): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction("app.dobby.REMINDER").putExtra("id", id)
        return PendingIntent.getBroadcast(context, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAppIntent(): PendingIntent {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
