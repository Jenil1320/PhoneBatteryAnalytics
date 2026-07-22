package io.github.jenil1320.cellspan.data

/**
 * One instantaneous reading of the battery. Fields sourced from the public
 * Android APIs are always present; the `sysfs*` fields are only populated when
 * the protected fuel-gauge nodes happen to be readable (rooted / permissive
 * ROM), and are null on stock unrooted devices.
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
    val sysfsAvailable: Boolean = false,
)
