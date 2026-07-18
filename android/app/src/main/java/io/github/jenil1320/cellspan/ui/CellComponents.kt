package io.github.jenil1320.cellspan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Mono

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Cell.Surface)
            .border(1.dp, Cell.Border, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) { content() }
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

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, color: Color = Cell.TextPrimary) {
    Column(modifier) {
        Text(value, color = color, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), color = Cell.TextFaint, fontFamily = Mono, fontSize = 9.5.sp, letterSpacing = 0.6.sp)
    }
}

/** A bordered vitals tile: label on top, big value, optional sub-line. */
@Composable
fun VitalTile(label: String, value: String, sub: String = "", modifier: Modifier = Modifier, accent: Color = Cell.TextPrimary) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Cell.BgElevated)
            .border(1.dp, Cell.Border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            SectionLabel(label)
            Text(value, color = accent, fontFamily = Mono, fontSize = 20.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (sub.isNotEmpty()) {
                Text(sub, color = Cell.TextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = Cell.TextMuted, fontSize = 13.sp)
        Text(value, color = Cell.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
