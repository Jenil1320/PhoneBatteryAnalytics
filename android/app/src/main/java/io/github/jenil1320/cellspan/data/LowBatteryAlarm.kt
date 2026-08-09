package io.github.jenil1320.cellspan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
 * Low-battery reminder: notifies the user to plug in once the battery falls to their
 * chosen level. The mirror image of [ChargeAlarm], and it uses the same shape — a short
 * WorkManager loop rather than a foreground service — so the two behave alike.
 *
 * One difference matters. The charge alarm only runs while the phone is plugged in, which
 * keeps it out of Doze; this one runs on battery, where Android may defer a job until the
 * next maintenance window. Polling on a fixed short interval would therefore cost real
 * power for little gain, so the interval scales with how far the level still has to fall:
 * far away we check rarely, close to the target we check often. Overshoot near the target
 * is bounded by [NEAR_MINUTES] of drain.
 */
object LowBatteryAlarm {
    const val CHANNEL_ID = "low_battery_alarm"
    private const val WORK_NAME = "low_battery_check"
    private const val NOTIF_ID = 4202

    /** Check interval when the level is almost at the target — bounds the overshoot. */
    private const val NEAR_MINUTES = 3L

    /** Input key carrying how many reminders have already been sent this discharge. */
    const val KEY_REMINDERS = "reminders_sent"

    /** Gap between repeat reminders, and how many to send before going quiet. */
    const val REMIND_MINUTES = 10L
    const val MAX_REMINDERS = 4

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Low battery reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminds you to plug in when the battery falls to your chosen level."
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    /** Start (or restart) the polling loop — call when running on battery with a level set. */
    fun scheduleCheck(context: Context, delayMinutes: Long = 0L, remindersSent: Int = 0) {
        val req = OneTimeWorkRequestBuilder<LowBatteryWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_REMINDERS to remindersSent))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Queue the next check, spaced by how many points are left to fall. The bands are
     * deliberately coarse: the drain rate is not knowable in advance, so a cheap estimate
     * that errs towards checking too often near the target is worth more than a precise
     * one that wakes the phone all day.
     */
    fun scheduleNext(context: Context, levelPct: Int, target: Int) {
        val gap = levelPct - target
        val minutes = when {
            gap > 25 -> 30L
            gap > 10 -> 15L
            gap > 3 -> 6L
            else -> NEAR_MINUTES
        }
        scheduleCheck(context, minutes)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Restart the loop if the reminder is on and the phone is on battery. The plug/unplug
     * broadcast normally owns this, but it is missed while the app is force-stopped, so
     * app start re-arms it. Deliberately reads the sticky battery broadcast rather than
     * [BatteryReader]: this runs on the main thread at startup and must not touch sysfs.
     */
    fun resume(context: Context) {
        if (AppPrefs.lowBatteryAlert(context) <= 0) return
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (!plugged) scheduleCheck(context, 0)
    }

    /**
     * @param ongoing pins the notification so it cannot be swiped away and lost. The final
     *        reminder is dismissible: once the loop stops nothing is left to clear a pinned
     *        one if the plug-in broadcast is ever missed.
     */
    fun notifyReached(context: Context, level: Int, target: Int, ongoing: Boolean = true) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Battery at $level%")
            .setContentText("Down to your $target% reminder — plug in to avoid a deep discharge.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Stays until the phone is plugged in, same as the unplug reminder — a swipe
            // should not be able to lose it. The loop and PowerReceiver both clear it.
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

/** One tick of the low-battery loop. Reschedules itself until the level is hit or the phone is plugged in. */
class LowBatteryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val target = AppPrefs.lowBatteryAlert(ctx)
        if (target <= 0) return Result.success()                  // reminder turned off
        val snap = BatteryReader.read(ctx)
        // Plugged, not status: right after an unplug the status string can still read
        // "Charging" for a moment, and one early read would kill the loop silently — with
        // nothing left to restart it, the user would simply never get the reminder.
        if (snap.pluggedIn) {
            // The loop clears its own notification rather than trusting the plug-in
            // broadcast to do it, so a missed broadcast cannot pin a stale "plug in"
            // banner on a phone that is already charging.
            LowBatteryAlarm.cancelNotification(ctx)
            return Result.success()                                // plugged in: stop the loop
        }
        if (snap.levelPct <= target) {
            val sent = inputData.getInt(LowBatteryAlarm.KEY_REMINDERS, 0)
            val last = sent + 1 >= LowBatteryAlarm.MAX_REMINDERS
            LowBatteryAlarm.notifyReached(ctx, snap.levelPct, target, ongoing = !last)
            // Keep asking while the phone is still on battery below the level. Spaced
            // wider than the unplug reminder: a discharge runs for hours, not minutes.
            if (!last) LowBatteryAlarm.scheduleCheck(ctx, LowBatteryAlarm.REMIND_MINUTES, sent + 1)
            return Result.success()
        }
        LowBatteryAlarm.scheduleNext(ctx, snap.levelPct, target)   // not there yet: check again later
        return Result.success()
    }
}
