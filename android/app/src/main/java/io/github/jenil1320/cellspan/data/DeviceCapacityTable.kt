package io.github.jenil1320.cellspan.data

/**
 * Published factory design capacities (mAh) keyed by [android.os.Build.MODEL].
 *
 * A sandboxed app cannot read `charge_full_design` from the fuel gauge, so when
 * that value isn't available we fall back to this table to compute an estimated
 * State of Health. Values are the manufacturers' typical-capacity ratings.
 * Unknown models simply yield a null design capacity (SoH shown as unavailable).
 */
object DeviceCapacityTable {

    private val byModel: Map<String, Int> = mapOf(
        // Google Pixel
        "Pixel 5" to 4080,
        "Pixel 5a" to 4680,
        "Pixel 6" to 4614,
        "Pixel 6 Pro" to 5003,
        "Pixel 6a" to 4410,
        "Pixel 7" to 4355,
        "Pixel 7 Pro" to 5000,
        "Pixel 7a" to 4385,
        "Pixel 8" to 4575,
        "Pixel 8 Pro" to 5050,
        "Pixel 8a" to 4492,
        "Pixel 9" to 4700,
        "Pixel 9 Pro" to 4700,
        "Pixel 9 Pro XL" to 5060,
        // Samsung Galaxy S (model codes vary by region; common ones)
        "SM-S911B" to 3900,   // S23
        "SM-S916B" to 4700,   // S23+
        "SM-S918B" to 5000,   // S23 Ultra
        "SM-S921B" to 4000,   // S24
        "SM-S928B" to 5000,   // S24 Ultra
        // OnePlus
        "CPH2449" to 5000,    // OnePlus 11
        // Nothing
        "A063" to 4700,       // Phone (2)
    )

    /** Look up a design capacity in mAh; null if the model is unknown. */
    fun designCapacityMah(model: String?): Int? {
        if (model.isNullOrBlank()) return null
        byModel[model]?.let { return it }
        // Case-insensitive fallback.
        val key = byModel.keys.firstOrNull { it.equals(model, ignoreCase = true) }
        return key?.let { byModel[it] }
    }
}
