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
    private const val STRICTNESS = "alert_strictness"           // GuardPolicy.Strictness name
    private const val TEMP_ALERT = "temp_alert_c"               // 0 = off
    private const val GUARD_STATE = "guard_"                    // prefix for the live guard state

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

    // ── Battery guard ─────────────────────────────────────────────────────────

    /**
     * How hard the band reminders push. GENTLE — tell me once — is the default.
     *
     * Repeating assumes the user is ignoring the reminder. Far more often they simply
     * cannot act: no charger to hand, driving, in a meeting. A phone that buzzes every few
     * minutes at someone who has no charger is not being helpful, and the thing they turn
     * off is the app.
     */
    fun strictness(c: Context): GuardPolicy.Strictness {
        migrateStrictnessOnce(c)
        return runCatching { GuardPolicy.Strictness.valueOf(p(c).getString(STRICTNESS, null) ?: "") }
            .getOrDefault(GuardPolicy.Strictness.GENTLE)
    }

    private const val STRICTNESS_MIGRATED = "alert_strictness_migrated"

    /**
     * Move existing installs off the old repeating default, exactly once.
     *
     * Changing a default only helps new users: anyone who already had the app has
     * PERSISTENT written to preferences from when that was the default, so they would keep
     * being nagged every ten minutes and would have no idea why the release notes claimed
     * otherwise. This rewrites that one value, and only that one — an INSISTENT setting is
     * a deliberate choice and is left alone, and once the flag is set a user who later
     * picks PERSISTENT on purpose keeps it.
     */
    private fun migrateStrictnessOnce(c: Context) {
        val prefs = p(c)
        if (prefs.getBoolean(STRICTNESS_MIGRATED, false)) return
        val stored = prefs.getString(STRICTNESS, null)
        prefs.edit().apply {
            if (stored == GuardPolicy.Strictness.PERSISTENT.name) {
                putString(STRICTNESS, GuardPolicy.Strictness.GENTLE.name)
            }
            putBoolean(STRICTNESS_MIGRATED, true)
        }.apply()
    }

    fun setStrictness(c: Context, s: GuardPolicy.Strictness) =
        p(c).edit().putString(STRICTNESS, s.name).apply()

    /**
     * Overheat alert threshold in °C, or null when off.
     *
     * Off by default, deliberately. Any armed alert keeps the guard's foreground service
     * running, and that puts a permanent card in the shade — not something to do to
     * someone who never asked for it. The Settings card offers
     * [GuardPolicy.DEFAULT_TEMP_ALERT_C] as the obvious choice when they do.
     */
    fun tempAlertC(c: Context): Float? = p(c).getFloat(TEMP_ALERT, 0f).takeIf { it >= 30f }

    fun setTempAlertC(c: Context, v: Float?) =
        p(c).edit().putFloat(TEMP_ALERT, v ?: 0f).apply()

    /** True when either end of the band is armed. */
    fun guardArmed(c: Context): Boolean =
        chargeAlarmTarget(c) > 0 || lowBatteryAlert(c) > 0

    private const val DELIVERED_FOR = "guard_delivered_for"

    /**
     * Which situation the last reminder was delivered for, as an opaque key.
     *
     * This is what lets the guard shut itself down instead of sitting in the notification
     * shade once its work is finished — and, just as importantly, what lets it start again
     * without anything having to remember to reset it.
     *
     * It replaced a plain "already done" boolean, which was a trap. That flag could only be
     * cleared by code that was running, and the whole point of the flag was to stop the
     * guard running; a plug event missed while the app was asleep therefore left it stuck
     * true, and the reminder never fired again on any level. Comparing a key describing the
     * *current* situation against the one that was last announced has no such state to go
     * stale: the moment the cable moves or the user changes a target, the keys differ and
     * the episode is new.
     */
    fun deliveredFor(c: Context): String? = p(c).getString(DELIVERED_FOR, null)
    fun setDeliveredFor(c: Context, key: String?) =
        p(c).edit().putString(DELIVERED_FOR, key).apply()

    /** The guard's live state, persisted so a service restart does not re-alert immediately. */
    fun guardState(c: Context): GuardPolicy.State {
        val pr = p(c)
        return GuardPolicy.State(
            activeKind = runCatching {
                GuardPolicy.AlertKind.valueOf(pr.getString(GUARD_STATE + "kind", null) ?: "")
            }.getOrNull(),
            lastAlertMs = pr.getLong(GUARD_STATE + "last", 0L),
            audibleCount = pr.getInt(GUARD_STATE + "count", 0),
            lastTempAlertMs = pr.getLong(GUARD_STATE + "temp_last", 0L),
            tempArmed = pr.getBoolean(GUARD_STATE + "temp_armed", true),
        )
    }

    fun setGuardState(c: Context, s: GuardPolicy.State) {
        p(c).edit()
            .putString(GUARD_STATE + "kind", s.activeKind?.name)
            .putLong(GUARD_STATE + "last", s.lastAlertMs)
            .putInt(GUARD_STATE + "count", s.audibleCount)
            .putLong(GUARD_STATE + "temp_last", s.lastTempAlertMs)
            .putBoolean(GUARD_STATE + "temp_armed", s.tempArmed)
            .apply()
    }
}
