package io.github.jenil1320.cellspan.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import kotlin.math.roundToInt

/**
 * An app ranked by visible foreground time in a window (matches Digital Wellbeing),
 * with a rough estimated battery use in mAh (foreground time × the device's screen
 * power) when the power profile is available. The mAh is an estimate, not a
 * measurement — exact per-app figures need a privileged permission apps can't hold.
 */
data class UsageItem(val label: String, val foregroundMs: Long, val estMah: Int? = null)

/** Friendly names for common system packages whose label can't be resolved directly. */
private val FRIENDLY = mapOf(
    "com.google.android.apps.nexuslauncher" to "Home screen",
    "com.android.systemui" to "System UI",
    "com.google.android.gms" to "Google services",
    "com.google.android.googlequicksearchbox" to "Google",
    "com.android.chrome" to "Chrome",
    "com.android.settings" to "Settings",
)

/**
 * Per-app visible screen time via [UsageStatsManager.queryEvents], pairing each
 * foreground event with its next background event. This matches how Digital
 * Wellbeing counts "screen time" — unlike queryUsageStats(INTERVAL_DAILY), which
 * returns whole calendar-day buckets (over-counting the window) and sums
 * totalTimeInForeground (a different, larger number). Requires the user to grant
 * "Usage access". Still a proxy for battery use — not per-app mAh.
 */
object AppUsage {

    /**
     * How much of an unclosed foreground span to believe. Four hours: long enough for a
     * genuine film or a long drive with navigation up, short enough that a lost
     * background event cannot silently claim a whole day.
     */
    private const val MAX_OPEN_TAIL_MS = 4 * 60 * 60_000L

    fun hasAccess(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    fun top(context: Context, sinceMs: Long, now: Long, limit: Int = 7): List<UsageItem> = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(sinceMs, now)
        val fg = HashMap<String, Long>()          // package -> accumulated visible ms
        val openedAt = HashMap<String, Long>()     // package -> last foreground timestamp
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            @Suppress("DEPRECATION")
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> openedAt[pkg] = e.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = openedAt.remove(pkg)
                    if (start != null && e.timeStamp > start) {
                        fg[pkg] = (fg[pkg] ?: 0L) + (e.timeStamp - start).coerceAtMost(now - start)
                    }
                }
            }
        }
        // Close out apps still in the foreground at `now` — but only the one that plausibly
        // still is.
        //
        // A MOVE_TO_FOREGROUND without a matching MOVE_TO_BACKGROUND does not mean the app
        // is still open. The platform drops background events when a process is killed, and
        // some OEMs never emit them at all for their own apps. Crediting every orphan right
        // up to `now` therefore handed hours of "screen time" to apps the user had not
        // touched since morning, and on a bad ROM the totals exceeded the length of the
        // window itself. Only the most recent open can still be running, and even that is
        // capped so a stale event cannot inflate the list.
        val stillOpen = openedAt.maxByOrNull { it.value }
        if (stillOpen != null && now > stillOpen.value) {
            val span = (now - stillOpen.value).coerceAtMost(MAX_OPEN_TAIL_MS)
            fg[stillOpen.key] = (fg[stillOpen.key] ?: 0L) + span
        }
        fg.remove(context.packageName)

        val pm = context.packageManager
        // Device screen power (mA) → rough per-app mAh = screenMa × foreground hours.
        val screenMa = PowerProfileReader.screenMilliAmps(context)
        fg.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(limit).map { (pkg, ms) ->
            val label = FRIENDLY[pkg] ?: try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg.substringAfterLast('.') }
            val estMah = screenMa?.let { (it * (ms / 3_600_000.0)).roundToInt() }?.takeIf { it > 0 }
            UsageItem(label, ms, estMah)
        }
    } catch (e: Exception) { emptyList() }
}
