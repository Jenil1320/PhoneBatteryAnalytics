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
 * AccuBattery approach — without a persistent foreground service.
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            val nowCharging = action == Intent.ACTION_POWER_CONNECTED

            // Battery life saver first, and in its own try: exactly one of the two loops
            // runs at a time, and the one that stops also clears its notification. This
            // used to sit below the history writes, inside their try — so a database
            // hiccup swallowed it and left a "plug in" banner pinned to a charging phone.
            // Nothing here needs the database, so nothing here waits on it.
            try {
                if (nowCharging) {
                    LowBatteryAlarm.cancel(app)
                    LowBatteryAlarm.cancelNotification(app)
                    if (AppPrefs.chargeAlarmTarget(app) > 0) ChargeAlarm.scheduleCheck(app, 0)
                } else {
                    ChargeAlarm.cancel(app)
                    ChargeAlarm.cancelNotification(app)
                    // Off the cable, the root cap must never stay engaged — a phone that
                    // silently refuses to charge is the one failure this feature cannot have.
                    ChargeControl.releaseIfEngaged(app)
                    if (AppPrefs.lowBatteryAlert(app) > 0) LowBatteryAlarm.scheduleCheck(app, 0)
                }
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
                if (cc != null) when (action) {
                    Intent.ACTION_POWER_CONNECTED ->
                        SessionTracker.begin(app, cc, snap.levelPct)

                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (SessionTracker.active(app)) {
                            val startCc = SessionTracker.startCc(app)
                            val startLevel = SessionTracker.startLevel(app)
                            if (startCc > 0 && startLevel in 0..100) {
                                val measured = BatteryAnalytics.measuredCapacityMah(startCc, cc, startLevel, snap.levelPct)
                                if (measured != null && measured in 1000..12000) {
                                    HistoryStore(app).use {
                                        it.insertSession(nowTs, startLevel, snap.levelPct, measured)
                                    }
                                }
                            }
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
