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
                val cc = snap.chargeCounterUah ?: return@Thread
                when (action) {
                    Intent.ACTION_POWER_CONNECTED ->
                        SessionTracker.begin(app, cc, snap.levelPct, snap.timestampMs)

                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (SessionTracker.active(app)) {
                            val startCc = SessionTracker.startCc(app)
                            val startLevel = SessionTracker.startLevel(app)
                            if (startCc > 0 && startLevel in 0..100) {
                                val measured = BatteryAnalytics.measuredCapacityMah(
                                    startCc, cc, startLevel, snap.levelPct,
                                )
                                if (measured != null && measured in 1000..12000) {
                                    HistoryStore(app).use {
                                        it.insertSession(snap.timestampMs, startLevel, snap.levelPct, measured)
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
