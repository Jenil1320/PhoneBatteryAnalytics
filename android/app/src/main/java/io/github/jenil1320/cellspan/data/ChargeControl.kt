package io.github.jenil1320.cellspan.data

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Stops the charge at the user's limit — when the phone allows it.
 *
 * No public Android API can cut charging, so this works through the kernel's own
 * charging switch, which is writable only as root. On a rooted phone Cellspan finds the
 * switch once, remembers it, and the charge alarm loop drives it. On a stock phone every
 * probe fails fast and the loop falls back to reminders (see [ChargeAlarm]).
 *
 * Two switch shapes exist in the wild:
 *  - [Kind.LEVEL] — Pixel kernels expose `charge_stop_level` / `charge_start_level`
 *    (0–100). Written once per charge, the kernel then caps and holds the level itself,
 *    with zero overshoot.
 *  - [Kind.SWITCH] — most other kernels expose a boolean (`charging_enabled`,
 *    `input_suspend`, …). Cellspan flips it at the target and back a few points below,
 *    so the level rides just under the cap while plugged in.
 *
 * Every engaged cap is recorded before it is applied, so a crash, force-stop or reboot
 * can never strand a phone that silently refuses to charge: [releaseIfStale] restores
 * the kernel defaults whenever the cap has no business being active.
 */
object ChargeControl {
    private const val PREFS = "cellspan_chargectl"
    private const val K_KIND = "kind"                 // absent = never probed
    private const val K_NODE = "node"                 // LEVEL: sysfs dir · SWITCH: sysfs file
    private const val K_STOP_VAL = "stop_val"         // SWITCH only
    private const val K_RESUME_VAL = "resume_val"     // SWITCH only
    private const val K_ENGAGED = "engaged_target"    // 0 = cap not applied

    /** How far below the target a SWITCH cap lets the level fall before recharging. */
    const val RESUME_BELOW = 3

    private const val SU_TIMEOUT_S = 6L

    enum class Kind { NONE, LEVEL, SWITCH }

    /** Boolean charging switches seen across kernels: path, value that stops, value that resumes. */
    private val SWITCHES = listOf(
        Triple("/sys/class/power_supply/battery/charging_enabled", "0", "1"),
        Triple("/sys/class/power_supply/battery/battery_charging_enabled", "0", "1"),
        Triple("/sys/class/power_supply/battery/input_suspend", "1", "0"),
        Triple("/sys/class/power_supply/battery/charge_disable", "1", "0"),
        Triple("/sys/class/power_supply/battery/store_mode", "1", "0"),
        Triple("/sys/class/power_supply/battery/batt_slate_mode", "1", "0"),
        Triple("/sys/class/power_supply/ac/charging_enabled", "0", "1"),
    )

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cached verdict; [Kind.NONE] both for "no root" and "not probed yet". Never blocks. */
    fun kind(c: Context): Kind = runCatching { Kind.valueOf(p(c).getString(K_KIND, null) ?: "NONE") }
        .getOrDefault(Kind.NONE)

    fun hasProbed(c: Context): Boolean = p(c).contains(K_KIND)

    /** The target the cap is currently engaged for, or 0. */
    fun engagedTarget(c: Context): Int = p(c).getInt(K_ENGAGED, 0)

    /**
     * Look for a usable charging switch. Blocking (runs `su`) — never call on the main
     * thread. On a phone without root the exec fails immediately; on a rooted phone the
     * first call raises the root-permission dialog. The result is cached either way.
     */
    fun probe(c: Context): Kind {
        val levelProbe = "for f in /sys/devices/platform/soc/soc:google,charger/charge_stop_level " +
            "/sys/devices/platform/google,charger/charge_stop_level " +
            "/sys/devices/platform/*/charge_stop_level; do " +
            "if [ -f \"\$f\" ]; then echo \"\$f\"; break; fi; done"
        val level = su(levelProbe)?.trim()?.takeIf { it.startsWith("/") }
        if (level != null) {
            p(c).edit().putString(K_KIND, Kind.LEVEL.name)
                .putString(K_NODE, level.removeSuffix("/charge_stop_level")).apply()
            return Kind.LEVEL
        }
        val switchProbe = SWITCHES.joinToString("; ") { (path, _, _) ->
            "if [ -f $path ]; then echo $path; fi"
        }
        val found = su(switchProbe)?.lineSequence()?.firstOrNull { it.startsWith("/") }?.trim()
        if (found != null) {
            val (_, stop, resume) = SWITCHES.first { it.first == found }
            p(c).edit().putString(K_KIND, Kind.SWITCH.name).putString(K_NODE, found)
                .putString(K_STOP_VAL, stop).putString(K_RESUME_VAL, resume).apply()
            return Kind.SWITCH
        }
        p(c).edit().putString(K_KIND, Kind.NONE.name).putInt(K_ENGAGED, 0).apply()
        return Kind.NONE
    }

    /** Cached kind, probing once if this device has never been probed. Blocking. */
    fun kindProbing(c: Context): Kind = if (hasProbed(c)) kind(c) else probe(c)

    /**
     * Apply the cap for [target]. LEVEL writes the stop/start pair and the kernel holds
     * the level from then on; SWITCH cuts the charge now (call it at the target).
     * The engaged flag is written FIRST — if the write dies half way, cleanup still runs.
     */
    fun engage(c: Context, target: Int): Boolean {
        val node = p(c).getString(K_NODE, null) ?: return false
        p(c).edit().putInt(K_ENGAGED, target).apply()
        val ok = when (kind(c)) {
            Kind.LEVEL -> {
                val start = (target - RESUME_BELOW).coerceAtLeast(0)
                // start before stop: some kernels reject stop <= current start.
                su("echo $start > $node/charge_start_level 2>/dev/null; " +
                    "echo $target > $node/charge_stop_level") != null
            }
            Kind.SWITCH -> su("echo ${p(c).getString(K_STOP_VAL, "0")} > $node") != null
            Kind.NONE -> false
        }
        if (!ok) p(c).edit().putInt(K_ENGAGED, 0).apply()
        return ok
    }

    /** Restore the kernel's defaults so the phone charges normally again. */
    fun release(c: Context): Boolean {
        val node = p(c).getString(K_NODE, null) ?: return false
        val ok = when (kind(c)) {
            Kind.LEVEL -> su("echo 0 > $node/charge_start_level 2>/dev/null; " +
                "echo 100 > $node/charge_stop_level") != null
            Kind.SWITCH -> su("echo ${p(c).getString(K_RESUME_VAL, "1")} > $node") != null
            Kind.NONE -> false
        }
        if (ok) p(c).edit().putInt(K_ENGAGED, 0).apply()
        return ok
    }

    fun releaseIfEngaged(c: Context) {
        if (engagedTarget(c) > 0) release(c)
    }

    /**
     * Safety net for app start: an engaged cap only belongs on a phone that is plugged
     * in with a limit still set. Anything else (force-stop mid-charge, limit turned off
     * while the app was dead) gets the defaults back. Blocking — background thread only.
     */
    fun releaseIfStale(c: Context, pluggedIn: Boolean) {
        if (engagedTarget(c) > 0 && (!pluggedIn || AppPrefs.chargeAlarmTarget(c) <= 0)) release(c)
    }

    /** Run [script] as root; stdout on exit 0, null on any failure. Blocking. */
    private fun su(script: String): String? = try {
        val proc = ProcessBuilder("su", "-c", script).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(SU_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly(); null
        } else if (proc.exitValue() == 0) out else null
    } catch (e: Exception) {
        null
    }
}
