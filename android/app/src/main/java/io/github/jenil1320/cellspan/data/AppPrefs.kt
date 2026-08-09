package io.github.jenil1320.cellspan.data

import android.content.Context

/**
 * Small user-settings store: the onboarding flag, an optional design-capacity
 * override, and the two battery life saver levels. Shares the same private prefs
 * file as the other on-device trackers.
 */
object AppPrefs {
    private const val PREFS = "cellspan_prefs"
    private const val ONBOARDED = "onboarded"
    private const val DESIGN_OVERRIDE = "design_override_mah"   // 0 / absent = none
    private const val ALARM_TARGET = "charge_alarm_target"      // 0 = off
    private const val LOW_ALERT = "low_battery_alert"           // 0 = off
    private const val BG_SAMPLING = "background_sampling"       // hourly sample, user-controlled
    private const val BATTERY_START = "battery_start_ms"        // 0 / absent = unknown

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOnboarded(c: Context): Boolean = p(c).getBoolean(ONBOARDED, false)
    fun setOnboarded(c: Context) = p(c).edit().putBoolean(ONBOARDED, true).apply()

    /** A user-set design capacity in mAh, or null. Sanity-bounded to a plausible range. */
    fun designOverrideMah(c: Context): Int? = p(c).getInt(DESIGN_OVERRIDE, 0).takeIf { it in 1000..12000 }
    fun setDesignOverrideMah(c: Context, mah: Int?) {
        p(c).edit().apply {
            if (mah == null) remove(DESIGN_OVERRIDE) else putInt(DESIGN_OVERRIDE, mah.coerceIn(1000, 12000))
        }.apply()
    }

    /** Charge-alarm target level, 1..100, or 0 when the alarm is off. */
    fun chargeAlarmTarget(c: Context): Int = p(c).getInt(ALARM_TARGET, 0)
    fun setChargeAlarmTarget(c: Context, pct: Int) =
        p(c).edit().putInt(ALARM_TARGET, if (pct <= 0) 0 else pct.coerceIn(1, 100)).apply()

    /**
     * Low-battery reminder level, 1..50, or 0 when it is off. Bounded well below the
     * unplug target so the two ends of the life-saver band can never cross.
     */
    fun lowBatteryAlert(c: Context): Int = p(c).getInt(LOW_ALERT, 0)
    fun setLowBatteryAlert(c: Context, pct: Int) =
        p(c).edit().putInt(LOW_ALERT, if (pct <= 0) 0 else pct.coerceIn(1, 50)).apply()

    /** Whether the hourly background sample may run. On by default; the user can stop it. */
    fun backgroundSampling(c: Context): Boolean = p(c).getBoolean(BG_SAMPLING, true)
    fun setBackgroundSampling(c: Context, on: Boolean) =
        p(c).edit().putBoolean(BG_SAMPLING, on).apply()

    /**
     * When the battery started life (bought or made), as epoch millis, or null.
     * Stock Android hides the manufacture date, so without this the wear model has to
     * drop its age-and-heat term and cycling wrongly absorbs the whole loss.
     */
    fun batteryStartMs(c: Context): Long? = p(c).getLong(BATTERY_START, 0L).takeIf { it > 0L }
    fun setBatteryStartMs(c: Context, ms: Long?) {
        p(c).edit().apply {
            if (ms == null || ms <= 0L) remove(BATTERY_START) else putLong(BATTERY_START, ms)
        }.apply()
    }
}
