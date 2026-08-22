package io.github.jenil1320.cellspan

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.jenil1320.cellspan.data.BatteryGuard
import io.github.jenil1320.cellspan.data.BatteryGuardNotifications
import io.github.jenil1320.cellspan.data.ChargeControl
import io.github.jenil1320.cellspan.data.SampleScheduler

class CellspanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before anything can post to them, and creating a channel
        // that already exists is a no-op, so this is cheap on every start.
        BatteryGuardNotifications.ensureChannels(this)

        // Everything else touches SharedPreferences, WorkManager or a binder, and
        // Application.onCreate is on the main thread ahead of the first frame. Doing it
        // here used to add measurable time to a cold start on a slow phone, so it moves
        // off-thread; nothing in it needs to be finished before the UI appears.
        Thread {
            runCatching {
                // Re-arm the guard: a force-stop takes the service down without warning,
                // and the user would never know their limits had gone quiet.
                BatteryGuard.sync(this)

                // Root-cap safety net: if a force-stop left the kernel cap engaged with
                // nothing supervising it, put the defaults back so the phone charges.
                if (ChargeControl.engagedTarget(this) > 0) {
                    val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                    ChargeControl.releaseIfStale(this, plugged)
                }

                // Hourly background sample, honouring the user's switch in Settings.
                SampleScheduler.apply(this)
            }
        }.start()
    }
}
