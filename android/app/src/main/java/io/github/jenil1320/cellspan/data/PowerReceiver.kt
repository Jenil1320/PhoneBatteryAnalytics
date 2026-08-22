package io.github.jenil1320.cellspan.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jenil1320.cellspan.analytics.BatteryAnalytics

/**
 * Captures charge-session endpoints via the power connect/disconnect broadcasts,
 * which the OS delivers even when the app isn't running. On plug-in we record the
 * charge counter + level; on unplug we measure the session's real capacity (charge
 * added ÷ fraction gained) and store it. Averaging these gives a stable SoH — the
 * AccuBattery approach.
 *
 * Reminders are NOT this class's business: [BatteryGuardService] watches the battery
 * broadcast directly and sees every one-point change, which is the only way to hit a
 * threshold exactly. All this does for the guard is heal it if something killed it.
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            val nowCharging = action == Intent.ACTION_POWER_CONNECTED

            // Battery life saver first, and in its own try. Nothing here needs the
            // database, so nothing here waits on it — a database hiccup used to swallow
            // this block and leave a "plug in" banner pinned to a charging phone.
            //
            // The guard service owns the reminders now and reacts to the battery broadcast
            // itself, so the only job left here is to make sure it is actually running, and
            // to guarantee the kernel cap comes off the moment the cable does.
            try {
                if (!nowCharging) {
                    // Off the cable, the root cap must never stay engaged — a phone that
                    // silently refuses to charge is the one failure this feature cannot have.
                    ChargeControl.releaseIfEngaged(app)
                }
                // Best-effort heal. A plug event is NOT one of the broadcasts that exempt
                // an app from the Android 12+ background foreground-service restriction, so
                // this succeeds only where the app already holds an exemption (the
                // battery-optimisation allowlist) or the app happens to be visible. It costs
                // nothing to try, and it is one of several paths — MainActivity heals on
                // every foreground, and BootReceiver uses a genuinely exempt broadcast.
                BatteryGuard.ensureRunning(app)
            } catch (e: Exception) {
                // best-effort; never crash on a broadcast
            }

            try {
                val snap = BatteryReader.read(app)
                val cc = snap.chargeCounterUah          // may be null on some devices
                val nowTs = snap.timestampMs

                // Close the current charge/discharge segment into a History session, then
                // open the opposite one. Works even without the fuel-gauge charge counter
                // (the mAh delta is just left unknown).
                SegmentTracker.current(app)?.let { seg ->
                    val delta = if (cc != null && seg.startCc != null) cc - seg.startCc else null
                    HistoryStore(app).use {
                        it.recordSession(seg.startTs, nowTs, seg.startLevel, snap.levelPct, delta, seg.isCharge)
                    }
                }
                SegmentTracker.begin(app, nowTs, snap.levelPct, cc, nowCharging)

                // Session-based capacity measurement (needs the fuel-gauge charge counter).
                when (action) {
                    Intent.ACTION_POWER_CONNECTED ->
                        if (cc != null) SessionTracker.begin(app, cc, snap.levelPct)

                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (SessionTracker.active(app)) {
                            val startCc = SessionTracker.startCc(app)
                            val startLevel = SessionTracker.startLevel(app)
                            if (cc != null && startCc > 0 && startLevel in 0..100) {
                                val measured = BatteryAnalytics.measuredCapacityMah(startCc, cc, startLevel, snap.levelPct)
                                if (measured != null && measured in 1000..12000) {
                                    HistoryStore(app).use {
                                        it.insertSession(nowTs, startLevel, snap.levelPct, measured)
                                    }
                                }
                            }
                            // Cleared unconditionally, including when the gauge went quiet
                            // mid-charge. Leaving it active carried this charge's start
                            // point into the next one, so the following unplug measured a
                            // gain that spanned two unrelated sessions and produced a
                            // capacity figure with no physical meaning.
                            SessionTracker.clear(app)
                        }
                    }
                }
            } catch (e: Exception) {
                // best-effort; never crash on a broadcast
            } finally {
                pending.finish()
            }
        }.start()
    }
}
