package io.github.jenil1320.cellspan.data

import android.content.Context

/**
 * Small user-settings store: the onboarding flag, an optional design-capacity
 * override, and the charge-alarm target. Shares the same private prefs file as
 * the other on-device trackers.
 */
object AppPrefs {
    private const val PREFS = "cellspan_prefs"
    private const val ONBOARDED = "onboarded"
    private const val DESIGN_OVERRIDE = "design_override_mah"   // 0 / absent = none
    private const val ALARM_TARGET = "charge_alarm_target"      // 0 = off

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
}
