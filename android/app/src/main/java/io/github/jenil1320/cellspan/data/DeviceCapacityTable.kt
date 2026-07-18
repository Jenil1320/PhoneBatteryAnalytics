package io.github.jenil1320.cellspan.data

/**
 * Published factory design capacities (mAh).
 *
 * A sandboxed app cannot read `charge_full_design` from the fuel gauge, so when
 * that value isn't available we fall back to this table to compute an estimated
 * State of Health. Lookup is two-tier:
 *  1. exact [android.os.Build.MODEL] match (Pixels report friendly names), then
 *  2. a prefix match on the family code, which absorbs the regional suffix
 *     Samsung/others append (e.g. SM-S911B / S911U / S9110 all map to S23).
 * Unknown models yield null, and the app shows SoH as unavailable rather than
 * inventing a number.
 */
object DeviceCapacityTable {

    private val byModel: Map<String, Int> = mapOf(
        // Google Pixel (Build.MODEL is the marketing name)
        "Pixel 4" to 2800, "Pixel 4 XL" to 3700, "Pixel 4a" to 3140,
        "Pixel 5" to 4080, "Pixel 5a" to 4680,
        "Pixel 6" to 4614, "Pixel 6 Pro" to 5003, "Pixel 6a" to 4410,
        "Pixel 7" to 4355, "Pixel 7 Pro" to 5000, "Pixel 7a" to 4385,
        "Pixel 8" to 4575, "Pixel 8 Pro" to 5050, "Pixel 8a" to 4492,
        "Pixel 9" to 4700, "Pixel 9 Pro" to 4700, "Pixel 9 Pro XL" to 5060,
        "Pixel Fold" to 4821,
        // Nothing
        "A063" to 4700,        // Phone (2)
        "A065" to 5000,        // Phone (2a)
    )

    /** Family-code prefixes → capacity, matched with startsWith to absorb region suffixes. */
    private val byPrefix: Map<String, Int> = mapOf(
        // Samsung Galaxy S
        "SM-G991" to 4000, "SM-G996" to 4800, "SM-G998" to 5000,   // S21 / + / Ultra
        "SM-S901" to 3700, "SM-S906" to 4500, "SM-S908" to 5000,   // S22 / + / Ultra
        "SM-S911" to 3900, "SM-S916" to 4700, "SM-S918" to 5000,   // S23 / + / Ultra
        "SM-S921" to 4000, "SM-S926" to 4900, "SM-S928" to 5000,   // S24 / + / Ultra
        "SM-S931" to 4000, "SM-S936" to 4900, "SM-S938" to 5000,   // S25 / + / Ultra
        // Samsung Galaxy A (popular mid-range)
        "SM-A536" to 5000, "SM-A546" to 5000, "SM-A556" to 5000,   // A53 / A54 / A55
        // OnePlus
        "CPH2449" to 5000, "CPH2451" to 5000,                       // OnePlus 11
        "CPH2581" to 5400, "CPH2583" to 5400,                       // OnePlus 12
    )

    /** Look up a design capacity in mAh; null if the model is unknown. */
    fun designCapacityMah(model: String?): Int? {
        if (model.isNullOrBlank()) return null
        byModel[model]?.let { return it }
        byModel.keys.firstOrNull { it.equals(model, ignoreCase = true) }?.let { return byModel[it] }
        // Prefix match for family codes with regional suffixes.
        byPrefix.entries.firstOrNull { model.startsWith(it.key, ignoreCase = true) }?.let { return it.value }
        return null
    }
}
