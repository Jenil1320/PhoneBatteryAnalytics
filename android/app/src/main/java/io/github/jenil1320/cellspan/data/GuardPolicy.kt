package io.github.jenil1320.cellspan.data

/**
 * The decision core of the battery guard: given the live battery state and the user's
 * settings, decide what (if anything) to tell them right now.
 *
 * Deliberately free of every Android type so it can be unit-tested on the JVM. The
 * service ([BatteryGuardService]) owns the platform side — reading the battery, posting
 * notifications, driving the kernel cap — and calls straight into here for every choice.
 *
 * ## Why the thresholds are now exact
 * The old design polled with WorkManager on a fixed three-minute cadence. A 65 W charger
 * gains about four points a minute, so an 80% target announced itself at 90%+; on the
 * discharge side App Standby could defer a tick for hours. This core is instead driven by
 * ACTION_BATTERY_CHANGED, which the platform sends on every single point of change, so a
 * crossing is seen at the point it happens rather than at the next poll.
 */
object GuardPolicy {

    /** How hard the reminders push. The user picks this in Settings. */
    enum class Strictness { GENTLE, PERSISTENT, INSISTENT }

    /** Which reminder is (or should be) on screen. */
    enum class AlertKind { UNPLUG, PLUG_IN, CRITICAL, OVERHEAT }

    /**
     * Battery level at or below which the reminder changes its wording to say the phone is
     * about to shut down. It is a different message, not a louder one — the user's choice
     * about how often to be interrupted is still theirs at 4%.
     */
    const val CRITICAL_LEVEL = 5

    /**
     * Audible repeats before the reminder goes quiet but stays pinned. The condition
     * clears the notification by itself, so this is only a backstop against a phone that
     * buzzes forever if something upstream goes wrong.
     */
    const val MAX_AUDIBLE_REPEATS = 20

    /** Default temperature alert threshold, °C. Above ~45 °C a cell ages measurably faster. */
    const val DEFAULT_TEMP_ALERT_C = 45f

    /** Never re-alert about heat more than once in this window. */
    const val TEMP_REALERT_MS = 20 * 60_000L

    /** Heat must fall this far below the threshold before a new episode can alert. */
    const val TEMP_HYSTERESIS_C = 2.0f

    data class Config(
        /** Plug-in reminder level, 1..50, or 0 when off. */
        val lowTarget: Int,
        /** Unplug / stop-charging level, 1..100, or 0 when off. */
        val highTarget: Int,
        val strictness: Strictness,
        /** Temperature alert threshold in °C, or null when off. */
        val tempAlertC: Float?,
    )

    /** What the guard is currently saying, carried between evaluations. */
    data class State(
        val activeKind: AlertKind? = null,
        val lastAlertMs: Long = 0L,
        val audibleCount: Int = 0,
        val lastTempAlertMs: Long = 0L,
        /** True once heat has dropped back under the threshold, re-arming the alert. */
        val tempArmed: Boolean = true,
    )

    /** One live reading, as the service observed it. */
    data class Reading(
        val levelPct: Int,
        val pluggedIn: Boolean,
        val temperatureC: Float?,
        /** True when the kernel cap is genuinely holding the charge, so no nagging is needed. */
        val capHeld: Boolean = false,
    )

    /**
     * @param kind which reminder should be on screen, or null to clear it.
     * @param audible true when this posting should make noise. A repeat that is merely
     *        keeping the notification alive is silent, so the shade stays truthful
     *        without the phone buzzing every few seconds as the level ticks.
     * @param tempAlert true when a separate overheat notification should fire now.
     */
    data class Decision(
        val kind: AlertKind?,
        val audible: Boolean,
        val tempAlert: Boolean,
        val state: State,
    )

    /**
     * Gap between repeats.
     *
     * [Strictness.GENTLE] returns "never", and it is the default. Repeating assumes the
     * user is ignoring the reminder, and the far more common reason they have not acted is
     * that they *cannot* — no charger to hand, driving, in a meeting. Buzzing every few
     * minutes at someone with no charger is not a reminder, it is harassment, and the
     * thing they eventually silence is the app.
     *
     * The louder settings stay for people who deliberately choose them, and CRITICAL no
     * longer overrides the choice: if the user asked to be told once, being told once at
     * 4% is still what they asked for.
     */
    fun repeatMs(kind: AlertKind, s: Strictness): Long = when (kind) {
        AlertKind.OVERHEAT -> TEMP_REALERT_MS
        AlertKind.CRITICAL -> when (s) {
            Strictness.GENTLE -> Long.MAX_VALUE
            Strictness.PERSISTENT -> 5 * 60_000L
            Strictness.INSISTENT -> 2 * 60_000L
        }
        AlertKind.UNPLUG -> when (s) {
            Strictness.GENTLE -> Long.MAX_VALUE
            Strictness.PERSISTENT -> 10 * 60_000L
            Strictness.INSISTENT -> 4 * 60_000L
        }
        AlertKind.PLUG_IN -> when (s) {
            Strictness.GENTLE -> Long.MAX_VALUE
            Strictness.PERSISTENT -> 15 * 60_000L
            Strictness.INSISTENT -> 5 * 60_000L
        }
    }

    /**
     * Whether the guard still has a reason to be watching the battery.
     *
     * This is what keeps the ongoing card off the screen when nothing is about to happen.
     * The service exists to catch a threshold crossing exactly; once the crossing has been
     * announced, or when no threshold is anywhere near, it has no job and should not be
     * sitting in the notification shade implying it does.
     *
     * @param deliveredFor the [episodeKey] the last reminder was announced for, or null.
     *        Compared against this reading's own key, so a stale value simply stops
     *        matching rather than silencing the guard for good.
     */
    fun shouldWatch(cfg: Config, r: Reading, deliveredFor: String?): Boolean {
        if (deliveredFor != null && deliveredFor == episodeKey(cfg, r)) return false
        if (r.pluggedIn) {
            // Charging is fast — a 65 W charger crosses ten points in under three minutes —
            // so once the cable is in, watch all the way up rather than trying to time it.
            //
            // No `level < highTarget` test, deliberately. Plugging in when ALREADY above the
            // target is the moment the reminder is most useful, and requiring the level to
            // be below it meant the guard never started, so that user was never told at all.
            // The episode flag is what stops it, one reading later, once it has spoken.
            return cfg.highTarget > 0
        }
        // On battery the level falls over hours, so there is no reason to watch from 90%
        // down. Start only once the target is within reach.
        return cfg.lowTarget > 0 && r.levelPct > cfg.lowTarget &&
            r.levelPct <= cfg.lowTarget + WATCH_MARGIN_PCT
    }

    /**
     * An opaque description of the situation a reminder belongs to.
     *
     * Two readings share a key when telling the user again would say the same thing: same
     * cable state, same targets. Anything that makes the reminder newly relevant — the
     * cable moving, the user changing a limit — changes the key, and the guard wakes up.
     */
    fun episodeKey(cfg: Config, r: Reading): String =
        "${if (r.pluggedIn) "in" else "out"}:${cfg.highTarget}:${cfg.lowTarget}"

    /**
     * How far above the low target the guard starts watching. Fifteen points is roughly
     * an hour and a half of ordinary use, which is comfortably longer than the worst-case
     * delay before a background check can start the service.
     */
    const val WATCH_MARGIN_PCT = 15

    /** The reminder this reading calls for, or null when neither end of the band is breached. */
    fun kindFor(cfg: Config, r: Reading): AlertKind? = when {
        // A root-held charge needs no reminder: the cap already did what the reminder
        // would only have asked for.
        r.pluggedIn && r.capHeld -> null
        r.pluggedIn && cfg.highTarget > 0 && r.levelPct >= cfg.highTarget -> AlertKind.UNPLUG
        !r.pluggedIn && r.levelPct <= CRITICAL_LEVEL && cfg.lowTarget > 0 -> AlertKind.CRITICAL
        !r.pluggedIn && cfg.lowTarget > 0 && r.levelPct <= cfg.lowTarget -> AlertKind.PLUG_IN
        else -> null
    }

    /**
     * Decide what to do with this reading.
     *
     * The contract the rest of the app relies on: a reminder appears on the very first
     * reading that breaches the band, and disappears on the very first reading that does
     * not. Nothing in between is remembered, so a stale "plug in" banner cannot survive on
     * a charging phone and an unplug reminder cannot outlive the cable.
     */
    fun evaluate(cfg: Config, r: Reading, prev: State, nowMs: Long): Decision {
        val temp = evaluateTemp(cfg, r, prev, nowMs)
        val kind = kindFor(cfg, r)

        if (kind == null) {
            // Condition cleared — drop the reminder and reset its counters so the next
            // breach starts fresh rather than inheriting a spent repeat budget.
            return Decision(null, audible = false, tempAlert = temp.fired,
                state = prev.copy(activeKind = null, lastAlertMs = 0L, audibleCount = 0,
                    lastTempAlertMs = temp.lastMs, tempArmed = temp.armed))
        }

        // A change of reminder (plug-in escalating to critical, say) always speaks up.
        val changed = kind != prev.activeKind
        val due = nowMs - prev.lastAlertMs >= repeatMs(kind, cfg.strictness)
        // A change of alert always speaks, whatever the repeat budget says: an hour of
        // ignored plug-in reminders on the insistent setting spends the budget, and the
        // escalation to "about to shut down" would otherwise land silently — the loudest
        // setting making the most important alert the quietest.
        val budgetLeft = changed || prev.audibleCount < MAX_AUDIBLE_REPEATS
        val audible = (changed || due) && budgetLeft

        return Decision(
            kind = kind,
            audible = audible,
            tempAlert = temp.fired,
            state = State(
                activeKind = kind,
                lastAlertMs = if (audible) nowMs else prev.lastAlertMs,
                audibleCount = if (changed) 1 else if (audible) prev.audibleCount + 1 else prev.audibleCount,
                lastTempAlertMs = temp.lastMs,
                tempArmed = temp.armed,
            ),
        )
    }

    /**
     * Evaluate ONLY the heat condition, leaving every band field of [prev] untouched.
     *
     * The hourly sample worker uses this. It must not go through [evaluate], because that
     * also decides the charge-band reminder and rewrites `activeKind`, `lastAlertMs` and
     * `audibleCount` — so a background heat check would silently mark a band reminder as
     * "already given" without ever posting one, and the real alert would then arrive
     * without a sound or not at all.
     *
     * The temperature fields ARE shared with the service on purpose, so the two paths use
     * one hysteresis window and cannot both warn about the same hot episode.
     */
    fun evaluateHeat(cfg: Config, r: Reading, prev: State, nowMs: Long): Decision {
        val t = evaluateTemp(cfg, r, prev, nowMs)
        return Decision(
            kind = prev.activeKind,
            audible = false,
            tempAlert = t.fired,
            state = prev.copy(lastTempAlertMs = t.lastMs, tempArmed = t.armed),
        )
    }

    private data class Temp(val fired: Boolean, val lastMs: Long, val armed: Boolean)

    /**
     * Heat is judged independently of charge level: a phone can cook while charging, while
     * gaming, or in a car mount, and none of those depend on the band. Hysteresis plus a
     * re-alert window stops a cell hovering on the threshold from alerting every reading.
     */
    private fun evaluateTemp(cfg: Config, r: Reading, prev: State, nowMs: Long): Temp {
        val limit = cfg.tempAlertC ?: return Temp(false, prev.lastTempAlertMs, true)
        val t = r.temperatureC ?: return Temp(false, prev.lastTempAlertMs, prev.tempArmed)
        // Cooled back down: re-arm so the next hot episode is allowed to speak.
        if (t <= limit - TEMP_HYSTERESIS_C) return Temp(false, prev.lastTempAlertMs, true)
        if (t < limit) return Temp(false, prev.lastTempAlertMs, prev.tempArmed)
        val quiet = nowMs - prev.lastTempAlertMs < TEMP_REALERT_MS
        return if (prev.tempArmed && !quiet) Temp(true, nowMs, false)
        else Temp(false, prev.lastTempAlertMs, prev.tempArmed)
    }
}
