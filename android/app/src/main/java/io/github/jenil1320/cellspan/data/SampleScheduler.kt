package io.github.jenil1320.cellspan.data

import android.content.Context
import android.os.PowerManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the hourly background sample.
 *
 * Sampling is a user choice, so [AppPrefs.backgroundSampling] gates it: turning the
 * switch off cancels the work outright rather than leaving a job queued that quietly
 * does nothing. WorkManager reschedules its own jobs across reboots and app updates,
 * so no boot receiver is needed.
 */
object SampleScheduler {

    private const val WORK = "cellspan-sample"

    /** How often we ask for a sample. Android may still batch this under Doze. */
    const val INTERVAL_MINUTES = 60L

    /** Enqueue or cancel the periodic sample to match the user's preference. */
    fun apply(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!AppPrefs.backgroundSampling(context)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SampleWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
        // UPDATE keeps the existing job's identity while adopting the new interval, so an
        // install still holding the old 6-hour schedule moves to hourly without dropping
        // its next run (KEEP would silently retain the old period forever).
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * True when the system has been told not to throttle this app in the background.
     * Reading this needs no permission; only *requesting* it directly would, which is
     * why the UI sends the user to the system screen instead.
     */
    fun isUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
}
