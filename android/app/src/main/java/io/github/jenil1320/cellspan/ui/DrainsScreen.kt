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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.data.AppUsage
import io.github.jenil1320.cellspan.data.DrainCost
import io.github.jenil1320.cellspan.data.DrainItem
import io.github.jenil1320.cellspan.data.SystemDrains
import io.github.jenil1320.cellspan.data.UsageItem
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class DrainsData(val drains: List<DrainItem>, val hasUsage: Boolean, val apps: List<UsageItem>)

private fun costColor(c: DrainCost) = when (c) {
    DrainCost.HIGH -> Cell.Bad; DrainCost.MEDIUM -> Cell.Warn; DrainCost.LOW -> Cell.Accent
}
private fun fmtDur(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

@Composable
fun DrainsScreen() {
    val context = LocalContext.current
    var data by remember { mutableStateOf<DrainsData?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            data = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val access = AppUsage.hasAccess(context)
                DrainsData(
                    SystemDrains.read(context), access,
                    if (access) AppUsage.top(context, now - 24 * 3600 * 1000L, now) else emptyList(),
                )
            }
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Drains", color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Android reserves exact per-app battery figures for Settings. Here's what's on now, and apps ranked by recent screen time — a proxy for drain.",
            color = Cell.TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Active drains")
                Spacer(Modifier.height(10.dp))
                val drains = data?.drains ?: emptyList()
                if (drains.isEmpty()) Text("Reading…", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
                else drains.forEach { DrainRow(it) }
            }
        }

        Spacer(Modifier.height(18.dp))
        SurfaceCard {
            Column {
                SectionLabel("Apps by recent screen time")
                Spacer(Modifier.height(10.dp))
                when (data?.hasUsage) {
                    null -> Text("Reading…", color = Cell.TextFaint, fontFamily = Mono, fontSize = 11.sp)
                    true -> {
                        val apps = data?.apps ?: emptyList()
                        if (apps.isEmpty()) {
                            Text("No app usage recorded recently.", color = Cell.TextMuted, fontSize = 13.sp)
                        } else {
                            val max = apps.maxOf { it.foregroundMs }.coerceAtLeast(1L)
                            apps.forEach { AppUsageBar(it.label, it.foregroundMs.toFloat() / max, fmtDur(it.foregroundMs)) }
                            Spacer(Modifier.height(8.dp))
                            Text("Foreground time over roughly the last day — a proxy, not measured battery use.",
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
private fun AppUsageBar(label: String, fraction: Float, time: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Cell.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Accent))
        }
        Text(time, color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.width(48.dp))
    }
}
