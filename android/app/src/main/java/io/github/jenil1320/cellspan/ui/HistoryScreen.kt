package io.github.jenil1320.cellspan.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.jenil1320.cellspan.data.AppUsage
import io.github.jenil1320.cellspan.data.DrainCost
import io.github.jenil1320.cellspan.data.DrainItem
import io.github.jenil1320.cellspan.data.Session
import io.github.jenil1320.cellspan.data.SystemDrains
import io.github.jenil1320.cellspan.data.UsageItem
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class DrainsData(
    val drains: List<DrainItem>,
    val hasUsage: Boolean,
    val apps: List<UsageItem>,
    val sinceMs: Long = 0L,
)

private fun costColor(c: DrainCost) = when (c) {
    DrainCost.HIGH -> Cell.Bad; DrainCost.MEDIUM -> Cell.Warn; DrainCost.LOW -> Cell.Accent
}

@Composable
fun HistoryScreen(sessions: List<Session>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var data by remember { mutableStateOf<DrainsData?>(null) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                data = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val since = startOfToday()
                    val access = AppUsage.hasAccess(context)
                    DrainsData(
                        SystemDrains.read(context), access,
                        if (access) AppUsage.top(context, since, now) else emptyList(), since,
                    )
                }
                delay(10_000)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("History", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Every charge and discharge as a session, what's draining now, and today's apps by screen time.",
            color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))

        // ── Sessions ─────────────────────────────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        SectionLabel("Sessions")
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            SurfaceCard {
                Text("Charge and unplug your phone a few times, and each charge and discharge shows up here — with its time span, how much it moved, and the wear it added.",
                    color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        } else {
            sessions.forEach { SessionCard(it); Spacer(Modifier.height(10.dp)) }
        }

        // ── What's on now ────────────────────────────────────────────────────────
        Spacer(Modifier.height(10.dp))
        SurfaceCard {
            Column {
                SectionLabel("What's on now")
                Spacer(Modifier.height(10.dp))
                val drains = data?.drains ?: emptyList()
                if (drains.isEmpty()) Text("Reading…", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
                else drains.forEach { DrainRow(it) }
            }
        }

        // ── Estimated app drain (screen time) ────────────────────────────────────
        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Estimated app drain · today")
                data?.sinceMs?.takeIf { it > 0 }?.let { since ->
                    Text("Since ${Fmt.time(since)} · estimated from screen time",
                        color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.height(10.dp))
                when (data?.hasUsage) {
                    null -> Text("Reading…", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
                    true -> {
                        val apps = data?.apps ?: emptyList()
                        if (apps.isEmpty()) {
                            Text("No app usage recorded yet today.", color = Cell.TextMuted, fontSize = 13.sp)
                        } else {
                            val max = apps.maxOf { it.foregroundMs }.coerceAtLeast(1L)
                            apps.forEach { AppUsageBar(it.label, it.foregroundMs.toFloat() / max, Fmt.duration(it.foregroundMs), it.estMah) }
                            Spacer(Modifier.height(8.dp))
                            Text("Rough estimate: screen time × your device's screen power. Exact per-app mAh needs a system permission normal apps can't use.",
                                color = Cell.TextFaint, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                    false -> {
                        Text("Grant Usage Access to rank apps by recent screen time.",
                            color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Cell.AccentSoft)
                                .border(1.dp, Cell.BorderAccent, RoundedCornerShape(10.dp))
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                        ) {
                            Text("Grant Usage Access", color = Cell.Accent, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun SessionCard(s: Session) {
    val up = s.isCharge
    val accent = if (up) Cell.Good else Cell.Bad
    val deltaPct = s.endLevel - s.startLevel
    val mah = s.chargeDeltaUah?.let { (it / 1000.0).roundToInt() }
    val wear = abs(deltaPct) / 100.0
    val lo = minOf(s.startLevel, s.endLevel).coerceIn(0, 100)
    val hi = maxOf(s.startLevel, s.endLevel).coerceIn(0, 100)
    SurfaceCard {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${if (up) "Charge" else "Discharge"} · ${Fmt.duration(s.endMs - s.startMs)}",
                    color = Cell.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp)).background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("${s.startLevel}→${s.endLevel}%", color = accent, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Text("${if (deltaPct >= 0) "+" else ""}$deltaPct%", color = Cell.TextPrimary, fontFamily = Mono, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                mah?.let { Text("${if (it >= 0) "+" else ""}$it mAh", color = accent, fontFamily = Mono, fontSize = 12.sp) }
            }
            // Where in the 0–100% range the session moved.
            Row(Modifier.fillMaxWidth().height(5.dp).padding(top = 8.dp).clip(RoundedCornerShape(3.dp)).background(Cell.Track)) {
                if (lo > 0) Box(Modifier.weight(lo.toFloat()))
                Box(Modifier.weight((hi - lo).coerceAtLeast(1).toFloat()).fillMaxHeight().background(accent))
                if (hi < 100) Box(Modifier.weight((100 - hi).toFloat()))
            }
            Text("${if (up) "Added" else "Used"} ${String.format(java.util.Locale.US, "%.2f", wear)} cyc of wear · ended ${Fmt.time(s.endMs)}",
                color = Cell.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun DrainRow(d: DrainItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.on) costColor(d.cost) else Cell.TextFaint.copy(alpha = 0.4f)))
        Spacer(Modifier.width(12.dp))
        Text(d.name, color = if (d.on) Cell.TextPrimary else Cell.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(d.detail, color = Cell.TextMuted, fontFamily = Mono, fontSize = 12.sp)
        if (d.on) {
            Spacer(Modifier.width(10.dp))
            Text(d.cost.name.lowercase().replaceFirstChar { it.uppercase() }, color = costColor(d.cost), fontFamily = Mono, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AppUsageBar(label: String, fraction: Float, time: String, estMah: Int?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Cell.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Accent))
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
            Text(estMah?.let { "~$it mAh" } ?: time, color = Cell.TextMuted, fontFamily = Mono, fontSize = 10.sp)
            if (estMah != null) Text(time, color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.sp)
        }
    }
}
