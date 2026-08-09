package io.github.jenil1320.cellspan.data

/**
 * One instantaneous reading of the battery. Fields sourced from the public
 * Android APIs are always present; the fuel-gauge fields ([chargeFullUah],
 * [chargeFullDesignUah], [manufacturingEpoch]) are only populated when the
 * protected nodes happen to be readable (rooted / permissive ROM), and are
 * null on stock unrooted devices.
 */
data class BatterySnapshot(
    val timestampMs: Long,
    // Device identity (from android.os.Build — no permission, no input needed)
    val manufacturer: String,
    val model: String,
    val device: String,
    // Public battery info
    val levelPct: Int,
    val status: String,
    /**
     * Whether a charger is physically attached. Distinct from [status], and the one to
     * test when reacting to a plug or unplug: the plugged flag flips with the power
     * broadcast, while status can still read "Charging" for a moment afterwards, and can
     * read "Not charging" on a plugged phone the platform has decided to hold.
     */
    val pluggedIn: Boolean,
    val health: String,
    val technology: String?,
    val temperatureC: Float?,
    val voltageV: Float?,
    val currentUa: Long?,
    val chargeCounterUah: Long?,
    val cycleCount: Int?,
    // Design capacity read from the ROM's PowerProfile (works on most devices,
    // any Android version; null if the hidden API is blocked or absent).
    val designCapacityFromProfileMah: Int? = null,
    // Enriched fuel-gauge values (usually null on stock Android)
    val chargeFullUah: Long? = null,
    val chargeFullDesignUah: Long? = null,
    val manufacturingEpoch: Long? = null,
    /**
     * State of Health as the platform itself reports it, 0–100, or null. This is the
     * manufacturer's own figure rather than anything Cellspan derives, so when it is
     * present it outranks every other capacity source.
     */
    val stateOfHealthPct: Int? = null,
)
