package io.github.jenil1320.cellspan.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the battery guard back after a reboot.
 *
 * WorkManager reschedules its own jobs across a restart, but a foreground service is not
 * WorkManager's to restore — without this the guard would stay down until the user next
 * opened the app, which is exactly the stretch (a phone charging overnight after an
 * update) when they most expect it to be watching.
 *
 * Receiving BOOT_COMPLETED is one of the documented exemptions that lets an app start a
 * foreground service from the background, and the `specialUse` type the guard declares is
 * not among the types Android 15 blocks from this path.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
                // Several OEMs send this instead of, or as well as, BOOT_COMPLETED.
            "android.intent.action.QUICKBOOT_POWERON",
            -> runCatching {
                BatteryGuardNotifications.ensureChannels(context)
                BatteryGuard.sync(context)
                SampleScheduler.apply(context)
            }
        }
    }
}
