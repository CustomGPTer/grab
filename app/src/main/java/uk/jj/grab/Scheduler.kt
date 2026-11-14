package uk.jj.grab

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** "Start at a set time": one alarm for the earliest scheduled download; it re-arms itself for the next. */
object Scheduler {

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets (or clears) the alarm to match the queue. Call whenever scheduled tasks are added, started or removed. */
    fun arm(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = DownloadStore.nextScheduled()
        try {
            if (at == null) { alarms.cancel(intent(ctx)); return }
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            else
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))     // may run a few minutes late
        } catch (e: Exception) {
            Log.w(App.TAG, "could not set start-time alarm: ${e.message}")
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // The service takes whatever is now due and arms the alarm again for anything still to come.
        DownloadService.kick(context)
    }
}
