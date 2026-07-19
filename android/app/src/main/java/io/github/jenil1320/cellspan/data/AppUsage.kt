package io.github.jenil1320.cellspan.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** An app ranked by recent foreground time (a proxy for battery use). */
data class UsageItem(val label: String, val foregroundMs: Long)

/**
 * Top apps by foreground time via [UsageStatsManager]. This is screen time, a
 * proxy for battery use — a normal app cannot read true per-app battery mAh.
 * Requires the user to grant "Usage access" in system settings.
 */
object AppUsage {

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
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sinceMs, now) ?: emptyList()
        val agg = HashMap<String, Long>()
        for (s in stats) if (s.totalTimeInForeground > 0) {
            agg[s.packageName] = (agg[s.packageName] ?: 0L) + s.totalTimeInForeground
        }
        agg.remove(context.packageName)
        val pm = context.packageManager
        agg.entries.sortedByDescending { it.value }.take(limit).map { (pkg, ms) ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg.substringAfterLast('.') }
            UsageItem(label, ms)
        }
    } catch (e: Exception) { emptyList() }
}
