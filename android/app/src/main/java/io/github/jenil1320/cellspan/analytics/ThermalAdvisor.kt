package io.github.jenil1320.cellspan.analytics

/**
 * Battery temperature turned into a severity and a short list of things the user can
 * actually do about it.
 *
 * Heat is the fastest way to ruin a lithium-ion cell, and unlike cycle count it is
 * something the user can act on within seconds. A number alone does not help — "47°C" only
 * means something if you know whether that is bad and what to do. So this returns both.
 *
 * ## Where the thresholds come from
 * Cell manufacturers publish two different limits, and they matter at different moments:
 *
 *  - **Charge** is specified for roughly 0–45 °C. Above that, charging drives lithium
 *    plating and accelerated electrolyte breakdown, which is why phone makers throttle or
 *    stop charging when the pack gets hot.
 *  - **Discharge** is specified far wider, typically −20 to 60 °C. A hot phone that is
 *    merely being used is stressed, not in danger.
 *
 * Between them sits the ageing rate. Degradation follows an Arrhenius law, so as a rule of
 * thumb it roughly doubles for every 10 °C. A cell held at 45 °C loses capacity several
 * times faster than the same cell at 25 °C, and that loss does not come back.
 *
 * The bands below follow that: [Level.ELEVATED] is "this is costing you life", [Level.HOT]
 * is "you are outside the charge specification", [Level.CRITICAL] is "the phone will start
 * protecting itself", and [Level.DANGER] is "stop now".
 *
 * ## Why the advice is conditional
 * The right action depends on what is making the heat, and the app knows. Charging is
 * usually the dominant source and is also the worst thing to be doing while hot, so it is
 * always the first thing to stop. A phone that is hot on battery is hot because of load or
 * sunlight instead, and telling that user to "unplug" would be useless noise.
 *
 * Pure Kotlin, no Android types, so all of it is unit tested.
 */
object ThermalAdvisor {

    enum class Level {
        /** Nothing to say. */
        NORMAL,

        /** Warm enough that ageing is measurably faster. Worth knowing, not urgent. */
        ELEVATED,

        /** Past the charge specification for a lithium-ion cell. Act now. */
        HOT,

        /** The phone will begin throttling or refusing to charge. */
        CRITICAL,

        /** Well outside safe operation. Stop using it and let it cool. */
        DANGER,
    }

    /** Lower bound of each band, °C. */
    const val ELEVATED_C = 40f
    const val HOT_C = 45f
    const val CRITICAL_C = 50f
    const val DANGER_C = 55f

    /** Below freezing is its own hazard, and the advice is the opposite. */
    const val FREEZING_C = 0f

    data class Advice(
        val level: Level,
        /** One line naming the situation. */
        val headline: String,
        /** What to do, most effective first. Empty when there is nothing to do. */
        val actions: List<String>,
        /** Why it matters, in one sentence. */
        val why: String,
    )

    /**
     * @param tempC battery temperature, or null when the device does not report it.
     * @param charging whether a charger is attached — the single biggest lever.
     * @param powerWatts present draw or charge power, used to tell a heavy load apart
     *        from a phone that is hot for some external reason.
     * @param screenOn whether the display is on.
     */
    fun advise(
        tempC: Float?,
        charging: Boolean,
        powerWatts: Double? = null,
        screenOn: Boolean = true,
    ): Advice? {
        if (tempC == null) return null

        if (tempC < FREEZING_C) {
            return Advice(
                level = Level.HOT,   // a hazard band, though the cause is the opposite
                headline = "Battery below freezing",
                actions = listOfNotNull(
                    if (charging) "Unplug until the phone warms up" else null,
                    "Warm the phone in a pocket or indoors before charging",
                    "Do not use an external heat source",
                ),
                why = "Charging a frozen cell plates metallic lithium on the anode. That damage is permanent and it does not reverse when the phone warms up.",
            )
        }

        val level = when {
            tempC >= DANGER_C -> Level.DANGER
            tempC >= CRITICAL_C -> Level.CRITICAL
            tempC >= HOT_C -> Level.HOT
            tempC >= ELEVATED_C -> Level.ELEVATED
            else -> Level.NORMAL
        }
        if (level == Level.NORMAL) return null

        // Heavy load is the second lever after the charger. 4 W at the battery is roughly
        // sustained gaming or navigation with the screen bright; ordinary use sits well below.
        val heavyLoad = !charging && (powerWatts ?: 0.0) >= 4.0

        val actions = buildList {
            // Ordered by how much each one actually removes, not by how easy it is.
            if (charging) {
                add("Unplug the charger — this is the biggest single change you can make")
            }
            if (heavyLoad) {
                add("Close games, navigation or video — the load is what is heating it")
            } else if (!charging && screenOn) {
                add("Lock the screen and put the phone down for a few minutes")
            }
            add("Take the case off, so the heat has somewhere to go")
            add("Move it out of direct sun and off any warm surface")
            if (level >= Level.CRITICAL) {
                add("Do not put it in a fridge or freezer — condensation does more harm than the heat")
            }
        }

        val headline = when (level) {
            Level.ELEVATED -> "Battery running warm"
            Level.HOT -> "Battery too hot to charge safely"
            Level.CRITICAL -> "Battery temperature critical"
            Level.DANGER -> "Stop using the phone"
            else -> ""
        }

        val why = when (level) {
            Level.ELEVATED ->
                "Above about 40°C the cell ages noticeably faster. Every 10°C roughly doubles the rate, and that capacity does not come back."
            Level.HOT ->
                "45°C is the top of the range lithium-ion cells are rated to charge in. Charging above it drives permanent capacity loss."
            Level.CRITICAL ->
                "The phone will start throttling and may refuse to charge. Sustained time at this temperature does lasting damage."
            Level.DANGER ->
                "This is well outside safe operating temperature. Stop what you are doing and let the phone cool before using it again."
            else -> ""
        }

        return Advice(level, headline, actions, why)
    }

    /**
     * How far through the hazard range a temperature sits, 0..1, for colour interpolation.
     *
     * 0 at [ELEVATED_C] and 1 at [DANGER_C], so a gauge can shade continuously from the
     * normal colour to the alarm colour instead of jumping between discrete bands. A
     * reading below the elevated threshold returns 0.
     */
    fun severityFraction(tempC: Float?): Float {
        if (tempC == null) return 0f
        if (tempC <= ELEVATED_C) return 0f
        return ((tempC - ELEVATED_C) / (DANGER_C - ELEVATED_C)).coerceIn(0f, 1f)
    }
}
