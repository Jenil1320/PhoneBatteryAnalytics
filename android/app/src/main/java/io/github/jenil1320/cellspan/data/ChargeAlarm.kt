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
import androidx.work.workDataOf
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

    /** Input key carrying how many reminders have already been sent this charge. */
    const val KEY_REMINDERS = "reminders_sent"

    /** Gap between repeat reminders once the target is passed. */
    const val REMIND_MINUTES = 5L

    /**
     * How many times to repeat before going quiet. No app can stop a charge, so the only
     * lever left is to keep asking; 8 reminders covers the ~40 minutes a phone takes to
     * go from 80% to full. After that the notification stays up but stops making noise,
     * because a reminder that never ends is one the user turns off for good.
     */
    const val MAX_REMINDERS = 8

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The channel ID stays as it was, so an upgrading user keeps whatever sound and
            // importance they had set; only the label follows the new feature name.
            val ch = NotificationChannel(CHANNEL_ID, "Unplug reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminds you to unplug when the battery reaches your target level."
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    /** Start (or restart) the polling loop — call when plugged in with a target set. */
    fun scheduleCheck(context: Context, delayMinutes: Long = 0L, remindersSent: Int = 0) {
        val req = OneTimeWorkRequestBuilder<ChargeAlarmWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_REMINDERS to remindersSent))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /** Queue the next check in the loop. */
    fun scheduleNext(context: Context) = scheduleCheck(context, CHECK_MINUTES)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * @param ongoing keeps the notification pinned so it cannot be swiped away and lost.
     *        The final reminder is posted dismissible instead: once the loop stops there
     *        is nothing left to clear a pinned one if the unplug broadcast is ever missed,
     *        and a notification the user cannot remove is worse than one they can.
     */
    fun notifyReached(context: Context, level: Int, target: Int, ongoing: Boolean = true) {
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
            // The notification IS the charge cap here, so while the loop is still alive it
            // stays put rather than being swiped away and forgotten. Both the loop and
            // PowerReceiver clear it once the cable comes out.
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
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

/**
 * One tick of the charge-alarm loop. Runs until the phone is unplugged, first watching for
 * the target and then repeating the reminder while the phone stays above it.
 */
class ChargeAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val target = AppPrefs.chargeAlarmTarget(ctx)
        if (target <= 0) return Result.success()                  // alarm turned off
        val snap = BatteryReader.read(ctx)
        // Plugged, not status: a phone held at a cap reports "Not charging" while still on
        // the cable, and stopping the loop there would drop the reminder exactly when the
        // user most wants it.
        if (!snap.pluggedIn) {
            // The loop clears its own notification rather than trusting the unplug
            // broadcast to do it. That broadcast is missed if the app is force-stopped
            // mid-charge, which would otherwise pin a stale "unplug" banner indefinitely.
            ChargeAlarm.cancelNotification(ctx)
            return Result.success()                               // unplugged: stop the loop
        }
        if (snap.levelPct >= target) {
            val sent = inputData.getInt(ChargeAlarm.KEY_REMINDERS, 0)
            val last = sent + 1 >= ChargeAlarm.MAX_REMINDERS
            ChargeAlarm.notifyReached(ctx, snap.levelPct, target, ongoing = !last)
            // Keep asking while the cable is still in. This is the whole enforcement
            // mechanism — the reminder repeats instead of firing once and being missed.
            if (!last) ChargeAlarm.scheduleCheck(ctx, ChargeAlarm.REMIND_MINUTES, sent + 1)
            return Result.success()
        }
        ChargeAlarm.scheduleNext(ctx)                             // not there yet: check again soon
        return Result.success()
    }
}
