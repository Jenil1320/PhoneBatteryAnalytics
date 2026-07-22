package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono
import io.github.jenil1320.cellspan.ui.theme.Sans

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .shadow(5.dp, RoundedCornerShape(20.dp), spotColor = Color(0x18354F52), ambientColor = Color(0x12354F52))
            .clip(RoundedCornerShape(20.dp))
            .background(Cell.Surface)
            .border(1.dp, Cell.Border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) { content() }
}

/** A saturated KPI stat card — the coloured tiles from the reference dashboard. */
@Composable
fun KpiTile(label: String, value: String, fill: Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(18.dp), spotColor = Color(0x22354F52), ambientColor = Color(0x14354F52))
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        Text(label.uppercase(), color = Cell.OnTileMuted, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.8.sp)
        Text(value, color = Cell.OnTile, fontFamily = Sans, fontSize = 25.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 5.dp))
        if (sub != null) Text(sub, color = Cell.OnTileMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Cell.TextFaint) {
    Text(
        text.uppercase(), modifier = modifier, color = color,
        fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp,
    )
}

@Composable
fun ConditionPill(label: String, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.32f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * A bordered vitals tile: a metric glyph + label on top, big value, optional
 * sub-line, and an optional slim range gauge whose fill shows where the reading
 * sits within its normal span. Falls back to a small accent dot when no [metric]
 * is given, so older call sites are unchanged.
 */
@Composable
fun VitalTile(
    label: String,
    value: String,
    sub: String = "",
    modifier: Modifier = Modifier,
    accent: Color = Cell.TextPrimary,
    bg: Color = Cell.Surface,
    metric: Metric? = null,
    gauge: Float? = null,
) {
    Box(
        modifier
            .shadow(3.dp, RoundedCornerShape(16.dp), spotColor = Color(0x16354F52), ambientColor = Color(0x0E354F52))
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, Cell.Border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (metric != null) MetricGlyph(metric, accent, glyphSize = 15.dp)
                else Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                SectionLabel(label)
            }
            Text(value, color = accent, fontFamily = Mono, fontSize = 20.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (sub.isNotEmpty()) {
                Text(sub, color = Cell.TextSecondary, fontFamily = Mono, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
            }
            if (gauge != null) {
                MetricGauge(gauge, accent, Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Cell.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = Cell.TextMuted, fontSize = 13.sp)
        Text(value, color = valueColor, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Horizontal proportion bar with a right-aligned label and value. */
@Composable
fun HBar(label: String, fraction: Float, valueText: String, barColor: Color = Cell.Accent) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Cell.TextMuted, fontFamily = Mono, fontSize = 10.sp,
            modifier = Modifier.width(58.dp))
        Box(Modifier.weight(1f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(Cell.Track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(13.dp)
                .clip(RoundedCornerShape(4.dp)).background(barColor))
        }
        Text(valueText, color = Cell.TextFaint, fontFamily = Mono, fontSize = 10.sp,
            modifier = Modifier.width(38.dp))
    }
}
