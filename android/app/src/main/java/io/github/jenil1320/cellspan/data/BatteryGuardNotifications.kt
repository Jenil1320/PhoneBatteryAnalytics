package io.github.jenil1320.cellspan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jenil1320.cellspan.MainActivity
import io.github.jenil1320.cellspan.R

/**
 * Every notification the battery guard can post, and the channels they live on.
 *
 * Four channels rather than one, because they are genuinely different interruptions and
 * the user should be able to silence the quiet one without losing the loud one:
 *  - [CHANNEL_STATUS] — the silent, ongoing foreground-service card that keeps the guard
 *    alive and doubles as a glanceable readout.
 *  - [CHANNEL_BAND] — reaching either end of the charge band.
 *  - [CHANNEL_CRITICAL] — about to shut down. Alarm sound, alarm audio stream, and it
 *    bypasses Do Not Disturb, because at that point silence is the wrong answer.
 *  - [CHANNEL_HEAT] — the cell is running hot.
 */
object BatteryGuardNotifications {

    const val CHANNEL_STATUS = "guard_status"

    /**
     * A NEW id, deliberately, not the `charge_alarm` the deleted alarm classes used.
     *
     * Once a channel exists, `createNotificationChannel` may only change its name,
     * description and group. Importance, sound, lights and vibration are silently ignored
     * — the platform protects whatever the user has (or has not) chosen. Reusing the old
     * id therefore looked like it preserved people's settings, but what it actually did
     * was throw away every property of the reworked reminder for exactly the users who
     * had the old app: no IMPORTANCE_HIGH, no vibration, no buzz pattern, and no way for
     * the code to detect the difference. Fresh installs would have behaved one way and
     * upgrades another.
     */
    const val CHANNEL_BAND = "guard_band"

    /** The id the deleted ChargeAlarm/LowBatteryAlarm used; removed on first run. */
    private val LEGACY_CHANNELS = listOf("charge_alarm", "low_battery_alarm")

    const val CHANNEL_CRITICAL = "guard_critical"
    const val CHANNEL_HEAT = "guard_heat"

    /** The foreground-service card. Its ID is fixed by the service contract. */
    const val ID_STATUS = 4200
    const val ID_BAND = 4201
    const val ID_HEAT = 4203

    /** Two short pulses then a long one — distinct from a message, hard to mistake. */
    private val BUZZ = longArrayOf(0, 220, 140, 220, 140, 620)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // Retire the channels the old alarm classes owned, so an upgrading user is not
        // left with two "charge reminder" entries in system settings, one of them dead.
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Battery guard", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The always-on card that lets Cellspan watch your battery level exactly."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BAND, "Charge reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you the moment the battery reaches either end of your chosen band."
                enableVibration(true)
                vibrationPattern = BUZZ
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Critical battery", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when the phone is minutes from shutting down."
                enableVibration(true)
                vibrationPattern = BUZZ
                // The alarm stream and a DND bypass, because a reminder the user cannot
                // hear at 4% has failed at the only job it had.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEAT, "Overheat warning", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you when the battery is running hot enough to age it faster."
                enableVibration(true)
            }
        )
    }

    /**
     * Whether a notification on [channelId] can actually reach the user.
     *
     * Both halves matter and the old code only checked the first. A user can leave the
     * app's notifications enabled and still turn off one channel, in which case
     * `areNotificationsEnabled()` keeps returning true while every `notify()` is dropped
     * on the floor — the app believed the reminders were working when they were not.
     */
    fun canReach(context: Context, channelId: String): Boolean {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val ch = mgr.getNotificationChannel(channelId) ?: return true   // not created yet
        if (ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        // A channel inside a blocked group is silenced too, and reports importance normally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val gid = ch.group
            if (gid != null && mgr.getNotificationChannelGroup(gid)?.isBlocked == true) return false
        }
        return true
    }

    /** True when at least one alerting channel can still reach the user. */
    fun anyAlertChannelReachable(context: Context): Boolean =
        canReach(context, CHANNEL_BAND) || canReach(context, CHANNEL_CRITICAL)

    private fun openApp(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The ongoing card the foreground service runs on. Built even when notifications are
     * blocked — the platform requires a notification object to keep the service alive, and
     * a blocked one simply is not drawn.
     */
    fun statusNotification(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context, 0))
            .build()

    /**
     * Post one band reminder.
     *
     * @param audible false re-posts the same reminder silently so the level stays current
     *        without buzzing on every one-point change.
     */
    fun postBand(
        context: Context,
        kind: GuardPolicy.AlertKind,
        level: Int,
        target: Int,
        audible: Boolean,
    ) {
        val critical = kind == GuardPolicy.AlertKind.CRITICAL
        val channel = if (critical) CHANNEL_CRITICAL else CHANNEL_BAND
        if (!canReach(context, channel)) return

        val (title, text) = when (kind) {
            GuardPolicy.AlertKind.UNPLUG ->
                "Battery at $level%" to "Your $target% limit — unplug now to reduce wear."
            GuardPolicy.AlertKind.PLUG_IN ->
                "Battery at $level%" to "Down to your $target% reminder — plug in soon."
            GuardPolicy.AlertKind.CRITICAL ->
                "Battery critical — $level%" to "The phone is about to shut down. Plug in now."
            GuardPolicy.AlertKind.OVERHEAT ->
                "Battery hot" to "Let the phone cool down."
        }

        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (critical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            // Pinned while the condition holds. The service clears it on the first reading
            // that no longer breaches the band, so unlike the old polling loop there is no
            // path that can strand an un-swipeable notification.
            .setOngoing(true)
            .setAutoCancel(false)
            // setSilent on a repeat: the shade stays accurate, the phone stays quiet.
            .setSilent(!audible)
            .setOnlyAlertOnce(!audible)
            .setContentIntent(openApp(context, 1))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_BAND, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and here.
        }
    }

    /** The quiet variant for a root-held charge: the cap did the job, this only informs. */
    fun postHeld(context: Context, level: Int, target: Int) {
        if (!canReach(context, CHANNEL_BAND)) return
        val n = NotificationCompat.Builder(context, CHANNEL_BAND)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Charging stopped at $level%")
            .setContentText("Held at your $target% limit — fine to stay plugged in.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, 1))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_BAND, n)
        } catch (e: SecurityException) {
        }
    }

    fun postHeat(context: Context, tempC: Float, limitC: Float) {
        if (!canReach(context, CHANNEL_HEAT)) return
        val n = NotificationCompat.Builder(context, CHANNEL_HEAT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(String.format(java.util.Locale.US, "Battery at %.0f°C", tempC))
            .setContentText(
                String.format(
                    java.util.Locale.US,
                    "Above your %.0f°C limit. Unplug, close heavy apps or move out of the sun — heat is the fastest way to age a cell.",
                    limitC,
                )
            )
            .setStyle(NotificationCompat.BigTextStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 2))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_HEAT, n)
        } catch (e: SecurityException) {
        }
    }

    fun cancelBand(context: Context) =
        NotificationManagerCompat.from(context).cancel(ID_BAND)
}
