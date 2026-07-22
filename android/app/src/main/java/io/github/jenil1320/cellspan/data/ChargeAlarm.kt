package io.github.jenil1320.cellspan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.jenil1320.cellspan.MainActivity
import io.github.jenil1320.cellspan.R
import java.util.concurrent.TimeUnit

/**
 * Charge alarm: notifies the user to unplug when the battery reaches a target level.
 *
 * Rather than a persistent foreground service, it polls with a short WorkManager
 * loop that only runs while charging. A plugged-in phone is kept out of Doze, so
 * the loop fires reliably; on unplug the loop cancels itself. Overshoot is bounded
 * by the check interval times the charge rate.
 */
object ChargeAlarm {
    const val CHANNEL_ID = "charge_alarm"
    private const val WORK_NAME = "charge_alarm_check"
    private const val NOTIF_ID = 4201
    private const val CHECK_MINUTES = 3L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Charge alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminds you to unplug when the battery reaches your target level."
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    /** Start (or restart) the polling loop — call when plugged in with a target set. */
    fun scheduleCheck(context: Context, delayMinutes: Long = 0L) {
        val req = OneTimeWorkRequestBuilder<ChargeAlarmWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** Queue the next check in the loop. */
    fun scheduleNext(context: Context) = scheduleCheck(context, CHECK_MINUTES)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun notifyReached(context: Context, level: Int, target: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Battery at $level%")
            .setContentText("Reached your $target% target — unplug to reduce battery wear.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            // Notification permission was revoked between the check and here.
        }
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}

/** One tick of the charge-alarm loop. Reschedules itself until the target is hit or the phone is unplugged. */
class ChargeAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val target = AppPrefs.chargeAlarmTarget(ctx)
        if (target <= 0) return Result.success()                  // alarm turned off
        val snap = BatteryReader.read(ctx)
        val charging = snap.status == "Charging" || snap.status == "Full"
        if (!charging) return Result.success()                    // unplugged: stop the loop
        if (snap.levelPct >= target) {
            ChargeAlarm.notifyReached(ctx, snap.levelPct, target)
            return Result.success()                               // reached: stop until next plug-in
        }
        ChargeAlarm.scheduleNext(ctx)                             // not there yet: check again soon
        return Result.success()
    }
}
