package io.github.jenil1320.cellspan.data

import android.annotation.SuppressLint
import android.content.Context

/**
 * Reads component power figures from the ROM's PowerProfile (power_profile.xml),
 * the same non-root reflection used for design capacity. Values are the average
 * current in mA that Android itself uses to estimate battery use, so they let us
 * turn per-app foreground time into a rough mAh estimate without the privileged
 * BATTERY_STATS permission (which normal apps cannot hold).
 */
object PowerProfileReader {

    @SuppressLint("PrivateApi")
    private fun open(context: Context): Pair<Class<*>, Any>? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val inst = cls.getConstructor(Context::class.java).newInstance(context)
        cls to inst
    } catch (e: Throwable) {
        null
    }

    private fun avg(cls: Class<*>, inst: Any, key: String): Double? = try {
        cls.getMethod("getAveragePower", String::class.java).invoke(inst, key) as? Double
    } catch (e: Throwable) {
        null
    }

    /**
     * Estimated screen current in mA: the screen-on baseline plus half of the
     * extra full-brightness draw (a mid-brightness assumption). Null if the ROM
     * does not expose the profile. This is the dominant, most defensible term for
     * attributing drain to whichever app held the foreground.
     */
    fun screenMilliAmps(context: Context): Double? {
        val (cls, inst) = open(context) ?: return null
        val on = avg(cls, inst, "screen.on") ?: return null
        val full = avg(cls, inst, "screen.full") ?: 0.0
        return (on + full * 0.5).takeIf { it > 0.0 }
    }
}
