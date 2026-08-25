package io.github.jenil1320.cellspan.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.content.IntentFilter
import java.util.concurrent.TimeUnit

/**
 * The one way the rest of the app talks to [BatteryGuardService].
 *
 * Callers say "the settings changed, make reality match" and this decides whether that
 * means starting the service, stopping it, or nudging a running one. Keeping that in a
 * single place is what stops the old bug where Settings, the power broadcast and app
 * startup each had their own idea of when a loop should be running.
 */
object BatteryGuard {

    private const val WATCHDOG = "cellspan-guard-watchdog"

    /**
     * Start, stop or refresh the guard to match the user's current settings.
     *
     * Safe to call from anywhere the app is visible. Calling it from the background on
     * Android 12+ can throw, so the throw is caught: the watchdog picks the service back
     * up at the next allowed moment rather than the app crashing.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val armed = AppPrefs.guardArmed(app)
        val intent = Intent(app, BatteryGuardService::class.java)
        // No flag to reset. A changed target changes the episode key by itself, so the
        // previous "already told them" stops matching without anyone clearing it.
        if (!armed) {
            runCatching { app.stopService(intent) }
            BatteryGuardNotifications.cancelBand(app)
            cancelWatchdog(app)
            return
        }
        scheduleWatchdog(app)
        start(app, intent)
    }

    /**
     * Start the service only if it has something to watch right now. Never throws.
     *
     * The "right now" is the point. Starting whenever a reminder is merely *armed* meant
     * the card sat in the notification shade permanently, including at 70% on battery with
     * a 20% target — five hours before anything could possibly happen. It now starts when a
     * threshold is actually within reach and stands down once the reminder has been given.
     */
    fun ensureRunning(context: Context) {
        val app = context.applicationContext
        if (!needsWatching(app)) return
        if (isRunning(app)) return
        start(app, Intent(app, BatteryGuardService::class.java))
    }

    /**
     * Read the "already told them" key, clearing it the moment the situation has moved on.
     *
     * The key alone is not enough, because it repeats: deliver at "plugged, 80" and the
     * very next charge produces the same key, so the guard would decide it had already
     * spoken and stay asleep for good. Consuming it on the first reading that does NOT
     * match turns it into a one-shot — unplugging clears it, and the next plug-in starts
     * clean.
     */
    fun effectiveDeliveredKey(context: Context, currentKey: String): String? {
        val app = context.applicationContext
        val stored = AppPrefs.deliveredFor(app) ?: return null
        if (stored == currentKey) return stored
        AppPrefs.setDeliveredFor(app, null)
        return null
    }

    /**
     * Whether a threshold is close enough to be worth watching for.
     *
     * Reads the sticky battery broadcast rather than [BatteryReader], because callers
     * include a broadcast receiver and the main thread, and this must stay cheap.
     */
    fun needsWatching(context: Context): Boolean {
        val app = context.applicationContext
        if (!AppPrefs.guardArmed(app)) return false
        val sticky = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val raw = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (raw < 0 || scale <= 0) return false
        val reading = GuardPolicy.Reading(
            levelPct = (raw * 100 / scale).coerceIn(0, 100),
            pluggedIn = sticky.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0,
            temperatureC = null,
        )
        val cfg = GuardPolicy.Config(
            lowTarget = AppPrefs.lowBatteryAlert(app),
            highTarget = AppPrefs.chargeAlarmTarget(app),
            strictness = AppPrefs.strictness(app),
            tempAlertC = AppPrefs.tempAlertC(app),
        )
        val key = GuardPolicy.episodeKey(cfg, reading)
        return GuardPolicy.shouldWatch(cfg, reading, effectiveDeliveredKey(app, key))
    }

    private fun start(app: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
            else app.startService(intent)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when called from a
            // context with no exemption. Not fatal — the watchdog retries.
        }
    }

    /**
     * Whether our own service is alive.
     *
     * `getRunningServices` is deprecated and lies about other apps, but the platform still
     * reports an app's *own* services faithfully, which is all this needs. The alternative
     * — a static flag — is wrong precisely in the case that matters, because an OEM task
     * killer tears the process down without running any of our code.
     */
    @Suppress("DEPRECATION")
    fun isRunning(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = BatteryGuardService::class.java.name
        am.getRunningServices(Int.MAX_VALUE).any { it.service.className == name }
    } catch (e: Exception) {
        false
    }

    /**
     * True when the guard *should* be running but is not.
     *
     * The UI shows this, because a silently dead guard is the worst possible failure for
     * this feature: the user believes their limits are being watched and they are not.
     * See [scheduleWatchdog] for why the restart cannot always succeed on its own.
     */
    fun isStalled(context: Context): Boolean =
        AppPrefs.guardArmed(context) && !isRunning(context)

    /**
     * A quarter-hourly check that the guard is still up.
     *
     * Aggressive OEM battery managers (MIUI, EMUI, ColorOS and friends) kill foreground
     * services of apps they do not recognise, and they do it without warning. WorkManager
     * survives that, because the platform reschedules it, so it is the right thing to try
     * the restart from.
     *
     * ## The honest limitation
     * From Android 12 an app cannot start a foreground service from the background unless
     * it holds an exemption, and an ordinary scheduled job is NOT one of the exemptions —
     * only a visible app, a few specific broadcasts such as BOOT_COMPLETED, and an app on
     * the battery-optimisation allowlist qualify. So on a phone that has killed the guard
     * and has not been allowlisted, this worker's restart attempt throws and is swallowed,
     * and the guard stays down until the user next opens the app.
     *
     * That is why three other things exist: [BootReceiver] uses an exempt broadcast,
     * MainActivity heals the guard every time it comes to the foreground (always allowed),
     * and Settings asks for the battery-optimisation exemption, which makes this worker's
     * restart legal. [isStalled] lets the UI admit it when none of that has happened.
     */
    fun scheduleWatchdog(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<GuardWatchdogWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun cancelWatchdog(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG)
    }
}

/** Restarts the guard if something killed it. Does nothing when it is already running. */
class GuardWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { BatteryGuard.ensureRunning(applicationContext) }
        // Always success: a retry would back off to hours and lose the cadence, and the
        // next quarter-hourly run is a better retry than an exponential one.
        return Result.success()
    }
}
