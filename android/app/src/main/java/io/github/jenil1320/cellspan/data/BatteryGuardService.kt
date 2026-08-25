package io.github.jenil1320.cellspan.data

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jenil1320.cellspan.analytics.ScreenDrainAccumulator
import io.github.jenil1320.cellspan.analytics.ThermalAdvisor
import java.util.Locale

/**
 * The battery guard: a foreground service that watches ACTION_BATTERY_CHANGED and acts on
 * the exact reading the platform reports.
 *
 * ## Why a service replaced the polling loops
 * The old design used two self-rescheduling WorkManager chains. Both were structurally
 * incapable of hitting a threshold on the nose:
 *  - the charge side polled every three minutes, and a 65 W charger gains roughly four
 *    points a minute, so an 80% target announced itself somewhere in the high eighties;
 *  - the discharge side was an ordinary deferrable job, so App Standby and Doze could hold
 *    a tick back long past the level it was meant to catch.
 *
 * ACTION_BATTERY_CHANGED is broadcast by the platform on every single point of change and
 * cannot be registered in a manifest, so the only way to receive it continuously is from a
 * running process. A foreground service is that process. The crossing is therefore seen at
 * the point it happens: at 80%, not at 89%.
 *
 * ## Cost
 * The service does no polling, holds no wake lock and performs no I/O on the broadcast
 * path beyond reading the intent's own extras. It costs one ongoing notification, which
 * doubles as the live readout the app otherwise had no way to show.
 *
 * ## Lifetime
 * It runs only while the user has armed something ([AppPrefs.guardArmed]). Settings starts
 * and stops it; [BootReceiver] restarts it after a reboot; [GuardWatchdogWorker] restarts
 * it if an aggressive OEM task killer takes it down.
 */
class BatteryGuardService : Service() {

    /**
     * Root-cap work runs `su`, which blocks. It must never run on the main thread, and it
     * must be serialised against itself, so it gets one private thread rather than a pool.
     */
    private var capThread: HandlerThread? = null

    @Volatile private var capBusy = false

    /** The last text we drew on the status card, so we only redraw when it really changed. */
    private var shownTitle = ""
    private var shownArmed = ""

    /**
     * Set the instant the service starts winding down.
     *
     * onDestroy runs on the main thread while handle() may still be mid-flight on the
     * guard's own thread, and unregisterReceiver does not wait for an in-flight dispatch.
     * Without this flag, turning the reminders off at the wrong millisecond let handle()
     * post a band notification *after* onDestroy had cancelled it — an ongoing, unswipeable
     * reminder for a feature the user had just switched off, with nothing left running that
     * could ever clear it.
     */
    @Volatile private var stopping = false

    /**
     * Set when the guard is standing down *because it has just delivered a reminder*.
     *
     * onDestroy cancels the band notification, which is right when the user disarms the
     * feature — but catastrophically wrong here. The guard now stops immediately after
     * announcing a threshold, so the cancel wiped the very alert it had just posted and
     * the user saw nothing at all. The alert is left in place instead; it is dismissible,
     * so nothing needs to clear it.
     */
    @Volatile private var leaveAlertPosted = false

    /**
     * Splits drain between screen-on and screen-off time. Lives here because this is the
     * only component that sees both signals: every one-point level change, and every
     * screen transition. Nothing else in the app is running when the phone is idle.
     */
    private val screenDrain = ScreenDrainAccumulator()

    /** Database writes for the drain split — never on the broadcast thread. */
    private var dbThread: HandlerThread? = null

    /** Where both broadcasts are delivered, so none of the guard's work touches the UI thread. */
    private var workThread: HandlerThread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            runCatching { handle(i) }
        }
    }

    /**
     * Screen transitions. They are rare — a few dozen a day — and each one closes the
     * current drain window, which is what makes the on/off attribution meaningful rather
     * than "whatever state the phone was in when the percentage happened to tick".
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            runCatching { recordScreenDrain() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BatteryGuardNotifications.ensureChannels(this)
        // Foreground first, before anything that can throw: Android kills a service that
        // does not call startForeground within five seconds of being started.
        startInForeground("Battery guard", "Starting…")
        capThread = HandlerThread("cellspan-cap").apply { start() }
        dbThread = HandlerThread("cellspan-guard-db").apply { start() }
        workThread = HandlerThread("cellspan-guard").apply { start() }
        // Both receivers are delivered on the guard's own thread, not the main one.
        // The battery broadcast fires on every single point of change, and each delivery
        // reads preferences, evaluates the policy and posts a notification — small pieces
        // of work individually, but there is no reason for any of it to compete with the
        // UI, and reading the sticky battery intent is a binder call.
        val h = android.os.Handler(workThread!!.looper)
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            null, h, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // SCREEN_ON and SCREEN_OFF are protected broadcasts sent by the system only, so
        // they are exported by necessity; the filter still cannot be declared in a manifest.
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            null, h, ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing armed at all — stop rather than sit in the shade doing nothing.
        if (!AppPrefs.guardArmed(this)) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        // Settings changed the band: re-evaluate at once instead of waiting for the next
        // one-point change, which on an idle phone can be an hour away. Off-thread, because
        // onStartCommand runs on the main thread and reading the sticky intent is a binder call.
        workThread?.let { t ->
            android.os.Handler(t.looper).post {
                runCatching {
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { handle(it) }
                }
            }
        }
        // STICKY, so the platform brings the guard back if it reclaims memory.
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        capThread?.quitSafely()
        capThread = null
        dbThread?.quitSafely()
        dbThread = null
        workThread?.quitSafely()
        workThread = null
        // Clear the band reminder ONLY when it is not the reminder we just delivered.
        // A pinned notification left by a dead service would be unclearable, but the
        // "told you once" alert is posted dismissible precisely so it can safely outlive
        // the guard — and cancelling it here made the whole feature silent.
        if (!leaveAlertPosted) BatteryGuardNotifications.cancelBand(this)
        super.onDestroy()
    }

    // ── the one decision path ─────────────────────────────────────────────────

    /**
     * Act on one battery broadcast. Reads only the intent extras, so this stays cheap
     * enough to run on every one-point change without touching the disk or the fuel gauge.
     */
    private fun handle(intent: Intent) {
        // The service is going away; anything posted from here would outlive the code
        // that could clear it.
        if (stopping) return
        val cfg = GuardPolicy.Config(
            lowTarget = AppPrefs.lowBatteryAlert(this),
            highTarget = AppPrefs.chargeAlarmTarget(this),
            strictness = AppPrefs.strictness(this),
            tempAlertC = AppPrefs.tempAlertC(this),
        )
        if (cfg.lowTarget <= 0 && cfg.highTarget <= 0) {
            // Release before disappearing. This branch is reached when the user turns
            // everything off, and stopping here used to remove the only component that
            // retried the release on each broadcast — so a single failed `su` could leave
            // a rooted phone refusing to charge until the app process was next created.
            if (ChargeControl.engagedTarget(this) > 0) postCap { ChargeControl.releaseIfEngaged(this) }
            stopping = true
            stopSelf()
            return
        }

        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        // A device that answers neither is telling us nothing; acting on a fabricated 0%
        // would fire a critical alert on a healthy phone.
        if (raw < 0 || scale <= 0) return
        val level = (raw * 100 / scale).coerceIn(0, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        // Drive the kernel cap first, off-thread. Its verdict feeds this reading's
        // decision, so a held charge does not also nag the user to unplug.
        val capHeld = driveCap(cfg, plugged, level)

        val reading = GuardPolicy.Reading(level, plugged, tempC, capHeld)
        val prev = AppPrefs.guardState(this)
        val d = GuardPolicy.evaluate(cfg, reading, prev, System.currentTimeMillis())
        AppPrefs.setGuardState(this, d.state)

        when {
            // Held for as long as the cap is engaged, not only while the level is still at
            // or above the target. A SWITCH cap cuts the charge AT the target, so the level
            // immediately decays a point or two below it — the old `highTarget in 1..level`
            // test then failed on the very next reading and cancelled the confirmation the
            // user had just been shown, while the status card still said "held".
            capHeld && plugged && cfg.highTarget > 0 ->
                BatteryGuardNotifications.postHeld(this, level, cfg.highTarget)
            d.kind == null -> BatteryGuardNotifications.cancelBand(this)
            else -> {
                val target = if (d.kind == GuardPolicy.AlertKind.UNPLUG) cfg.highTarget else cfg.lowTarget
                val once = GuardPolicy.repeatMs(d.kind, cfg.strictness) == Long.MAX_VALUE
                BatteryGuardNotifications.postBand(this, d.kind, level, target, d.audible, dismissible = once)
                // The reminder has been delivered. On the default "tell me once" setting
                // that is the whole job, so the episode is closed and the guard can stand
                // down — which is what takes its card out of the notification shade.
                if (GuardPolicy.repeatMs(d.kind, cfg.strictness) == Long.MAX_VALUE) {
                    AppPrefs.setDeliveredFor(this, GuardPolicy.episodeKey(cfg, reading))
                    leaveAlertPosted = true
                }
            }
        }
        if (d.tempAlert && tempC != null) {
            // The advice, not just the number. What to do about heat depends on what is
            // making it, and the service knows whether the phone is on a charger.
            val advice = ThermalAdvisor.advise(tempC, plugged)
            if (advice != null) {
                BatteryGuardNotifications.postHeat(
                    this, tempC, advice.headline, advice.actions, advice.why,
                )
            }
        }

        recordScreenDrain(level, plugged)

        // Nothing left to catch? Then stop, rather than sitting in the notification shade
        // implying the app is doing something. The guard exists to see a threshold
        // crossing exactly; once it has announced one, or when no threshold is anywhere
        // near, it has no reason to be running. BatteryGuard.sync starts it again when the
        // cable moves or the level comes back into range.
        val key = GuardPolicy.episodeKey(cfg, reading)
        if (!GuardPolicy.shouldWatch(cfg, reading, BatteryGuard.effectiveDeliveredKey(this, key))) {
            stopping = true
            stopSelf()
            return
        }

        updateStatus(cfg, level, plugged, tempC, capHeld)
    }

    // ── screen-on vs screen-off drain ─────────────────────────────────────────

    /**
     * Fold this moment into the screen-on / screen-off split.
     *
     * Called from both receivers. On a screen transition the level is not supplied, so it
     * is re-read from the sticky broadcast — cheap, and it keeps the two entry points from
     * drifting apart.
     */
    private fun recordScreenDrain(levelIn: Int = -1, pluggedIn: Boolean? = null) {
        val pm = getSystemService(PowerManager::class.java) ?: return
        var level = levelIn
        var plugged = pluggedIn
        if (level < 0 || plugged == null) {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val raw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw < 0 || scale <= 0) return
            level = raw * 100 / scale
            plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        val split = screenDrain.observe(
            System.currentTimeMillis(), level.coerceIn(0, 100),
            isScreenOn = pm.isInteractive, discharging = plugged != true,
        ) ?: return
        val day = dayKey(System.currentTimeMillis())
        val t = dbThread ?: return
        android.os.Handler(t.looper).post {
            runCatching {
                HistoryStore(this).use {
                    it.addScreenDischarge(day, split.onPct, split.offPct, split.onMs, split.offMs)
                }
            }
        }
    }

    /** Local time, matching the rule every other day bucket in the app uses. */
    private fun dayKey(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date(ms))

    // ── kernel cap (rooted phones only) ───────────────────────────────────────

    /**
     * Ask [ChargeControl] to hold the charge at the target, if this phone can.
     *
     * Returns the cap's state as of the *previous* tick, not this one: the `su` calls are
     * slow and must not run on the broadcast path, so the work is posted to the cap thread
     * and the flag is read from the persisted engaged target. One reading of latency is
     * harmless — the reminder path simply speaks up once and then falls silent when the
     * cap engages.
     */
    private fun driveCap(cfg: GuardPolicy.Config, plugged: Boolean, level: Int): Boolean {
        val engagedFor = ChargeControl.engagedTarget(this)
        if (cfg.highTarget <= 0 || !plugged) {
            // Off the cable, or with the limit turned off, the cap must never stay on: a
            // phone that silently refuses to charge is the one failure this cannot have.
            if (engagedFor > 0) postCap { ChargeControl.releaseIfEngaged(this) }
            return false
        }
        // Only ever use the cached verdict here. Probing runs `su`, which raises a root
        // prompt, and a broadcast that arrives every one percent is the wrong place for it.
        val kind = ChargeControl.kind(this)
        when (kind) {
            ChargeControl.Kind.NONE -> return false
            ChargeControl.Kind.LEVEL ->
                if (engagedFor != cfg.highTarget) postCap { ChargeControl.engage(this, cfg.highTarget) }
            ChargeControl.Kind.SWITCH -> when {
                engagedFor <= 0 && level >= cfg.highTarget ->
                    postCap { ChargeControl.engage(this, cfg.highTarget) }
                engagedFor > 0 && level <= cfg.highTarget - ChargeControl.RESUME_BELOW ->
                    postCap { ChargeControl.release(this) }
            }
        }

        // Two questions, and they are not the same one.
        //
        // "Is the cap WRITTEN?" is the persisted engaged target, re-read after the posts
        // above. It must come from there and never from the level, because the writes are
        // asynchronous and can be dropped — postCap discards a request while an earlier one
        // is still running, and a phone that has lost root (a revoked Magisk grant, a
        // restarted su daemon) fails the write outright. Returning the intended outcome
        // told the user "charging stopped at 80%" AND withheld the unplug reminder while
        // the phone charged on to 100%.
        //
        // "Is the cap RESTRAINING the charge right now?" is what the caller actually needs,
        // and for a LEVEL cap it is a different question. A Pixel-style kernel takes its
        // stop level the moment the cable goes in, at any charge level, and then charges
        // normally up to it — so a written cap at 25% is not holding anything. Answering
        // the first question in place of the second announced "Charging stopped at 25% —
        // held at your 80% limit" to someone whose phone was charging perfectly normally,
        // and suppressed their reminders on the way up. A SWITCH cap is only ever written
        // AT the target, so for that kind the two questions coincide.
        val written = ChargeControl.engagedTarget(this) == cfg.highTarget
        return when (kind) {
            ChargeControl.Kind.LEVEL -> written && level >= cfg.highTarget
            // A SWITCH cap holds the level just under the target, dropping at most
            // RESUME_BELOW points before the charge is let back on. Further down than that
            // and the cap is not holding anything — the release write has been failing and
            // the phone is draining on the cable. Reporting "held" there put "charging
            // stopped at your 80% limit" on screen at 12%.
            ChargeControl.Kind.SWITCH ->
                written && level >= cfg.highTarget - ChargeControl.RESUME_BELOW - 1
            ChargeControl.Kind.NONE -> false
        }
    }

    /**
     * Run one blocking root command off the broadcast thread, at most one at a time. Root
     * writes take seconds; queueing them up behind a broadcast that fires every one percent
     * would build a backlog that outlives the charge it was meant to control.
     */
    private fun postCap(block: () -> Unit) {
        val t = capThread ?: return
        if (capBusy) return
        capBusy = true
        android.os.Handler(t.looper).post {
            try { block() } catch (e: Exception) { } finally { capBusy = false }
        }
    }

    // ── the ongoing status card ───────────────────────────────────────────────

    private fun updateStatus(cfg: GuardPolicy.Config, level: Int, plugged: Boolean, tempC: Float?, capHeld: Boolean) {
        val armed = buildString {
            when {
                cfg.lowTarget > 0 && cfg.highTarget > 0 -> append("Guarding ${cfg.lowTarget}–${cfg.highTarget}%")
                cfg.lowTarget > 0 -> append("Reminding at ${cfg.lowTarget}%")
                cfg.highTarget > 0 -> append("Reminding at ${cfg.highTarget}%")
                else -> append("Watching temperature")
            }
            if (capHeld) append(" · charge held")
        }
        val state = when {
            capHeld -> "held"
            plugged -> "charging"
            else -> "on battery"
        }
        val temp = tempC?.let { String.format(Locale.US, " · %.0f°C", it) } ?: ""
        val title = "$level% · $state$temp"

        // Redraw only when something a reader would notice has changed — the broadcast can
        // fire several times a minute while charging and each notify() is a binder call.
        //
        // The key is the rendered text itself, not the level. Comparing the level alone
        // meant a phone sitting at 100% kept saying "charging" after the cable came out,
        // because the number had not moved; the same held for a cell heating up while the
        // level stood still.
        if (title == shownTitle && armed == shownArmed) return
        shownTitle = title
        shownArmed = armed
        startInForeground(title, armed)
    }

    private fun startInForeground(title: String, text: String) {
        val n = BatteryGuardNotifications.statusNotification(this, title, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, BatteryGuardNotifications.ID_STATUS, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                ServiceCompat.startForeground(this, BatteryGuardNotifications.ID_STATUS, n, 0)
            }
        } catch (e: Exception) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when a start is
            // attempted from the background without an exemption. Stopping is the correct
            // response: MainActivity will bring the guard back the next time the app is
            // visible, which is always allowed. The flag goes first, so a battery broadcast
            // already queued on the guard thread cannot post a notification into the gap
            // before onDestroy runs.
            stopping = true
            stopSelf()
        }
    }
}
