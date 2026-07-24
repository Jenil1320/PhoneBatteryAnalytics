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

    // Constructing PowerProfile parses power_profile.xml, and it was being done on every
    // battery read. The profile is a ROM constant, so build it once and keep it. The
    // instance is published before the "already tried" flag, so another thread never sees
    // the flag with no instance behind it.
    @Volatile private var profile: Pair<Class<*>, Any>? = null
    @Volatile private var profileTried = false

    @SuppressLint("PrivateApi")
    private fun open(context: Context): Pair<Class<*>, Any>? {
        profile?.let { return it }
        if (profileTried) return null
        val p = try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            cls to cls.getConstructor(Context::class.java).newInstance(context.applicationContext)
        } catch (e: Throwable) {
            null
        }
        profile = p
        profileTried = true
        return p
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

    /**
     * Design capacity (mAh) from the same profile — the value every ROM ships in
     * `frameworks/base` as `battery.capacity`. Unlike the fuel-gauge nodes it needs no
     * SELinux exception, so State of Health works even for phones not in the published
     * capacity table. Best-effort: any failure (blocked hidden API, missing method)
     * simply returns null and the caller falls back to that table.
     */
    fun batteryCapacityMah(context: Context): Int? {
        val (cls, inst) = open(context) ?: return null
        val mAh = try {
            cls.getMethod("getBatteryCapacity").invoke(inst) as? Double
        } catch (e: Throwable) {
            avg(cls, inst, "battery.capacity")
        }
        return mAh?.takeIf { it > 1000.0 && it < 20000.0 }?.toInt()
    }
}
