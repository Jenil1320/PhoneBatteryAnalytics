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
 * The charge limit's engine: a short WorkManager loop that only runs while charging.
 * A plugged-in phone is kept out of Doze, so the loop fires reliably; on unplug it
 * cancels itself. Overshoot is bounded by the check interval times the charge rate.
 *
 * With root the loop drives [ChargeControl] and the charge genuinely stops at the
 * target. Without root no app can cut the power, so the loop falls back to a pinned,
 * repeating unplug reminder — the strongest lever a stock phone allows.
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
     * How many times to repeat before going quiet. Without root no app can stop a charge,
     * so the only lever left is to keep asking; 8 reminders covers the ~40 minutes a phone
     * takes to go from 80% to full. After that the notification stays up but stops making
     * noise, because a reminder that never ends is one the user turns off for good.
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
     * Restart the loop if a limit is set and the phone is already plugged in. The
     * plug/unplug broadcast normally owns this, but it is missed while the app is
     * force-stopped, so app start re-arms it — the mirror of [LowBatteryAlarm.resume].
     */
    fun resume(context: Context) {
        if (AppPrefs.chargeAlarmTarget(context) <= 0) return
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (plugged) scheduleCheck(context, 0)
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

    /**
     * The quiet variant for a root-held charge: the cap is doing the work, so this only
     * informs. Alerts once, then updates silently as the level rides under the limit.
     */
    fun notifyHeld(context: Context, level: Int, target: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Charging stopped at $level%")
            .setContentText("Held at your $target% limit — fine to stay plugged in.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
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
 * One tick of the charge-limit loop. Runs until the phone is unplugged. With root it
 * engages the kernel cap and supervises it; without root it watches for the target and
 * then repeats the unplug reminder while the phone stays above it.
 */
class ChargeAlarmWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val target = AppPrefs.chargeAlarmTarget(ctx)
        if (target <= 0) {
            ChargeControl.releaseIfEngaged(ctx)                   // limit turned off mid-charge
            return Result.success()
        }
        val snap = BatteryReader.read(ctx)
        // Plugged, not status: a phone held at a cap reports "Not charging" while still on
        // the cable, and stopping the loop there would drop the limit exactly when the
        // user most wants it.
        if (!snap.pluggedIn) {
            // Undo the cap and clear the notification here rather than trusting the unplug
            // broadcast to do it. That broadcast is missed if the app is force-stopped
            // mid-charge, which would otherwise strand a phone that refuses to charge.
            ChargeControl.releaseIfEngaged(ctx)
            ChargeAlarm.cancelNotification(ctx)
            return Result.success()                               // unplugged: stop the loop
        }
        if (holdWithRoot(ctx, target, snap.levelPct)) {
            ChargeAlarm.scheduleNext(ctx)                         // cap engaged: keep supervising
            return Result.success()
        }
        if (snap.levelPct >= target) {
            val sent = inputData.getInt(ChargeAlarm.KEY_REMINDERS, 0)
            val last = sent + 1 >= ChargeAlarm.MAX_REMINDERS
            ChargeAlarm.notifyReached(ctx, snap.levelPct, target, ongoing = !last)
            // Keep asking while the cable is still in. Without root this repetition is
            // the whole enforcement mechanism.
            if (!last) ChargeAlarm.scheduleCheck(ctx, ChargeAlarm.REMIND_MINUTES, sent + 1)
            return Result.success()
        }
        ChargeAlarm.scheduleNext(ctx)                             // not there yet: check again soon
        return Result.success()
    }

    /**
     * Drive the root cap for this tick. True = the cap is managing the charge and the
     * reminder path must stay quiet; false = no root (or a write failed), fall back to
     * reminders so the limit never silently does nothing.
     */
    private fun holdWithRoot(ctx: Context, target: Int, level: Int): Boolean =
        when (ChargeControl.kindProbing(ctx)) {
            ChargeControl.Kind.NONE -> false
            ChargeControl.Kind.LEVEL -> {
                // Written once per charge (or when the target moves); the kernel then
                // stops at the target and tops back up on its own.
                if (ChargeControl.engagedTarget(ctx) != target && !ChargeControl.engage(ctx, target)) false
                else {
                    if (level >= target) ChargeAlarm.notifyHeld(ctx, level, target)
                    true
                }
            }
            ChargeControl.Kind.SWITCH -> {
                val engaged = ChargeControl.engagedTarget(ctx) > 0
                when {
                    // At the target: cut the charge. A failed write falls back to reminders.
                    !engaged && level >= target ->
                        if (ChargeControl.engage(ctx, target)) {
                            ChargeAlarm.notifyHeld(ctx, level, target); true
                        } else false
                    // Sagged a few points below: let it top back up to the target.
                    engaged && level <= target - ChargeControl.RESUME_BELOW -> {
                        ChargeControl.release(ctx); true
                    }
                    engaged -> { ChargeAlarm.notifyHeld(ctx, level, target); true }
                    else -> true                                  // below target, still charging
                }
            }
        }
}
