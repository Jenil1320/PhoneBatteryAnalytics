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
            try {
                val snap = BatteryReader.read(app)
                val cc = snap.chargeCounterUah          // may be null on some devices
                val nowTs = snap.timestampMs
                val nowCharging = action == Intent.ACTION_POWER_CONNECTED

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

                // Charge alarm: start the polling loop on plug-in (if a target is set),
                // stop it and clear any notification on unplug.
                if (nowCharging) {
                    if (AppPrefs.chargeAlarmTarget(app) > 0) ChargeAlarm.scheduleCheck(app, 0)
                } else {
                    ChargeAlarm.cancel(app)
                    ChargeAlarm.cancelNotification(app)
                }

                // Session-based capacity measurement (needs the fuel-gauge charge counter).
                if (cc != null) when (action) {
                    Intent.ACTION_POWER_CONNECTED ->
                        SessionTracker.begin(app, cc, snap.levelPct, nowTs)

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
