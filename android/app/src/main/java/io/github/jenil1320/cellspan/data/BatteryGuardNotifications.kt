package io.github.jenil1320.cellspan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 *  - [CHANNEL_CRITICAL] — about to shut down. Its own channel so the user can silence it
 *    separately from the ordinary band reminder.
 *  - [CHANNEL_HEAT] — the cell is hot enough to damage it.
 *
 * None of them makes a sound. They vibrate. See [silentVibration].
 */
object BatteryGuardNotifications {

    const val CHANNEL_STATUS = "guard_status"

    /**
     * Channel ids carry a version suffix, and it is bumped whenever their SOUND or
     * VIBRATION has to change.
     *
     * Once a channel exists, `createNotificationChannel` may only update its name,
     * description and group. Importance, sound, lights and vibration are silently ignored
     * from then on — the platform protects whatever the user has, or has not, chosen. So
     * switching these alerts to vibrate-only could not reach anyone who already had the
     * app: their channels would keep the sound they were created with, and no code could
     * detect the difference. A new id is the only way to change them, and the old ones are
     * deleted so nobody ends up with two entries for the same thing.
     */
    const val CHANNEL_BAND = "guard_band_v2"
    const val CHANNEL_CRITICAL = "guard_critical_v2"
    const val CHANNEL_HEAT = "guard_heat_v2"

    /** Every id this app has ever used for these alerts, removed on first run. */
    private val LEGACY_CHANNELS = listOf(
        "charge_alarm", "low_battery_alarm",          // the deleted ChargeAlarm/LowBatteryAlarm
        "guard_band", "guard_critical", "guard_heat", // v1 of the guard, created with sound
    )

    /** The foreground-service card. Its ID is fixed by the service contract. */
    const val ID_STATUS = 4200
    const val ID_BAND = 4201
    const val ID_HEAT = 4203


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
                silentVibration()
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Critical battery", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when the phone is minutes from shutting down."
                silentVibration()
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEAT, "Battery temperature", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you when the battery is hot enough to damage it, and says what to do."
                silentVibration()
            }
        )
    }

    /**
     * Vibrate, never make a sound.
     *
     * `setSound(null, null)` is the only way to silence a channel — a notification built
     * without a sound still plays the user's default tone, because the channel supplies it
     * on Android 8 and above. None of these alerts is worth a noise: a battery reminder is
     * not more urgent than the messages the user already gets, and one that chimes during
     * a meeting or at night is the reason apps get muted. A vibration is enough to notice
     * and impossible to resent.
     *
     * Set once here so no channel can be added later that quietly makes a sound.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun NotificationChannel.silentVibration() {
        setSound(null, null)
        enableVibration(true)
        enableLights(false)
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
    /**
     * @param dismissible true for a "tell me once" alert. It has to be swipeable, because
     *        the guard stands down straight after posting it and there will be nothing
     *        left running to take it away. A repeating alert stays pinned instead, since
     *        the service is still there to clear it the moment the user acts.
     */
    fun postBand(
        context: Context,
        kind: GuardPolicy.AlertKind,
        level: Int,
        target: Int,
        audible: Boolean,
        dismissible: Boolean = false,
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
        // Category, not sound: CATEGORY_ALARM on a channel with no alarm tone still tells
        // the system this matters, without making the phone shout.

        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Vibrate only, on every posting as well as on the channel: a channel created
            // by an older build keeps whatever sound it had, and the platform will not let
            // us change it afterwards.
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(null)
            .setOngoing(!dismissible)
            .setAutoCancel(dismissible)
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

    /**
     * The heat warning, carrying the same advice the Now screen shows.
     *
     * A bare "battery hot" tells the user nothing they cannot already feel. The value is in
     * the instruction, so the notification expands to the ranked action list from
     * [io.github.jenil1320.cellspan.analytics.ThermalAdvisor] — and the first action is the
     * one that removes the most heat, which while charging is always to unplug.
     */
    fun postHeat(
        context: Context,
        tempC: Float,
        headline: String,
        actions: List<String>,
        why: String,
    ) {
        if (!canReach(context, CHANNEL_HEAT)) return
        val body = buildString {
            actions.forEach { append("• ").append(it).append('\n') }
            append('\n').append(why)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_HEAT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(String.format(java.util.Locale.US, "%s — %.0f°C", headline, tempC))
            .setContentText(actions.firstOrNull() ?: why)
            // BigTextStyle, so the whole list is readable without opening the app. A user
            // acting on this is usually holding a hot phone and wants the answer now.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(null)
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
