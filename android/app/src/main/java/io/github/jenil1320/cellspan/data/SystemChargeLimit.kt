package io.github.jenil1320.cellspan.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The phone's own charge limit — the only thing on an unrooted device that genuinely stops
 * a charge instead of asking the user to.
 *
 * ## What the platform actually allows, measured
 * The key is `Settings.Secure.charge_optimization_mode`. On a Pixel 7 running Android 17,
 * writing 1 flips **Settings › Battery › Battery health › Charging optimization** to
 * "Limit to 80%" and writing 0 turns it off. That was verified on hardware, not inferred.
 *
 * The two halves are governed separately, and they do not match:
 *
 *  - **Writing works** with `WRITE_SECURE_SETTINGS`. Its protection level is
 *    `signature|privileged|**development**`, and that third flag means it can be granted
 *    to an ordinary app once over ADB, with no root, surviving reboots:
 *
 *        adb shell pm grant io.github.jenil1320.cellspan android.permission.WRITE_SECURE_SETTINGS
 *
 *  - **Reading is refused outright**, even holding that permission. The platform answers:
 *    *"Settings key charge_optimization_mode is not readable. From S+, settings keys
 *    annotated with @hide are restricted to system_server and system apps only, unless
 *    they are annotated with @Readable."* No permission an ordinary app can hold changes
 *    that.
 *
 * ## What that asymmetry means for the design
 * Cellspan can drive the limit but can never observe it. So it does not pretend to: it
 * records what it last wrote and presents that, and it says plainly that the phone's own
 * Settings screen is the authority if the two ever disagree. The alternative — inferring
 * the state from a read that always fails — is what made an earlier build tell a Pixel 7
 * that it "does not publish a charge limit" while the feature sat there working.
 *
 * The reminder is also kept as a backstop rather than being switched off, so a device
 * where the write lands but does nothing still tells the user at 80%.
 *
 * ## The limit is a switch, not a dial
 * The platform feature is fixed at 80%. It cannot be set to 85 or 90, so it can back the
 * app's limit only when the user's target is [SYSTEM_LIMIT_PCT]. At any other target the
 * UI says so rather than implying a cap that is not there.
 */
object SystemChargeLimit {

    /** The only level the platform feature supports. */
    const val SYSTEM_LIMIT_PCT = 80

    /** Verified on Pixel / Android 17. 0 = off, 1 = limit to 80%. */
    private const val KEY = "charge_optimization_mode"
    private const val ON = 1
    private const val OFF = 0

    private const val PREFS = "cellspan_prefs"
    private const val K_LAST_WRITTEN = "sys_charge_limit_on"

    /** How the system limit stands, as far as Cellspan can tell. */
    enum class State {
        /** No ADB grant, so Cellspan cannot touch it. The user can still set it by hand. */
        NOT_PERMITTED,

        /**
         * Cellspan can drive it but has never set it, so it does not know the current
         * value — and since Android refuses the read, it cannot find out. Distinct from
         * OFF_WRITABLE, because claiming "off" on a phone the user may already have set by
         * hand would be asserting something unknown.
         */
        UNKNOWN_WRITABLE,

        /** Cellspan can drive it, and last set it off. */
        OFF_WRITABLE,

        /** Cellspan can drive it, and last set it on. */
        ON_WRITABLE,
    }

    /**
     * The ADB command that unlocks the switch. Shown verbatim so the user can copy it —
     * a development-level permission has no runtime prompt.
     */
    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** True when the ADB grant is present, so the app can actually flip the switch. */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * What Cellspan last set the limit to. This is a record of our own action, not a
     * reading of the phone — see the class note. It is null until the app has ever set it.
     */
    fun lastWritten(context: Context): Boolean? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(K_LAST_WRITTEN)) p.getBoolean(K_LAST_WRITTEN, false) else null
    }

    fun state(context: Context): State {
        if (!canWrite(context)) return State.NOT_PERMITTED
        return when (lastWritten(context)) {
            true -> State.ON_WRITABLE
            false -> State.OFF_WRITABLE
            null -> State.UNKNOWN_WRITABLE
        }
    }

    /**
     * Turn the platform limit on or off.
     *
     * Returns whether the write itself was accepted. It cannot confirm the effect, because
     * the value is not readable back — so the caller must not describe this as verified,
     * and the app keeps its own reminder running as the check that the charge really did
     * stop.
     */
    fun setOn(context: Context, on: Boolean): Boolean = try {
        Settings.Secure.putInt(context.contentResolver, KEY, if (on) ON else OFF)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_LAST_WRITTEN, on).apply()
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The system Battery screen, for devices Cellspan cannot drive and for users who want
     * to check or change it themselves. The exact sub-page differs per manufacturer — on a
     * Pixel it is Battery › Battery health › Charging optimization — so the user lands on
     * Battery and the card names what to look for. POWER_USAGE_SUMMARY is documented and
     * resolves wherever the Settings app ships.
     */
    fun settingsIntent(): Intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
}
