package io.github.jenil1320.cellspan.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import io.github.jenil1320.cellspan.ui.theme.Sans

/** Shared header for the info sub-screens: a back control and a title. */
@Composable
private fun InfoHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("←", color = Cell.Accent, fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Text(title, color = Cell.TextPrimary, fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A titled explainer card: a glyph + term on top, then a plain-language body. */
@Composable
private fun TermCard(term: String, body: String, metric: Metric? = null) {
    Spacer(Modifier.height(12.dp))
    SurfaceCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (metric != null) MetricGlyph(metric, Cell.Accent, glyphSize = 17.dp)
                Text(term, color = Cell.TextPrimary, fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(body, color = Cell.TextSecondary, fontSize = 13.5.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 7.dp))
        }
    }
}

/** A short paragraph used inside the privacy screen. */
@Composable
private fun Para(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    SurfaceCard {
        Column {
            Text(title, color = Cell.TextPrimary, fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Plain-language reference for the numbers Cellspan shows. Written in short,
 * simple sentences (ASD-STE100 style): one idea per sentence, technical terms
 * named and then explained.
 */
@Composable
fun BatteryBasicsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        InfoHeader("Battery basics", onBack)
        Text("What the numbers in Cellspan mean, in simple words.",
            color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)

        TermCard("State of Health (SoH)",
            "This is how much charge your battery holds now, next to when it was new. A new battery is 100%. " +
                "At 80%, it holds four fifths of the original charge. Most phone batteries reach 80% after about 500 to 1000 full charges.",
            Metric.HEALTH)

        TermCard("Capacity (mAh)",
            "Capacity is how much charge a battery can hold. The unit is the milliamp-hour (mAh). A bigger number holds more charge. " +
                "\"Design capacity\" is the value from the factory. \"Full-charge capacity\" is what your battery holds today.",
            Metric.SOC)

        TermCard("Charge cycle & cycle count",
            "One charge cycle is one full 0% to 100% charge. Two half charges also count as one cycle. " +
                "The \"cycle count\" is the total number of cycles so far. More cycles cause more wear.",
            Metric.CYCLES)

        TermCard("Current (mA)",
            "Current is how fast charge moves in or out of the battery. The unit is the milliamp (mA). " +
                "A high current means fast charging or heavy use. In Cellspan, charging shows as a positive value and draining as a negative value.",
            Metric.CURRENT)

        TermCard("Voltage (V)",
            "Voltage is the electrical pressure of the battery. The unit is the volt (V). A phone battery is about 3.7 V. " +
                "The voltage rises near a full charge and falls when the battery is low.",
            Metric.VOLTAGE)

        TermCard("Power (W)",
            "Power is the rate of energy use. The unit is the watt (W). Power is voltage times current. " +
                "More power means faster charging, or faster draining.",
            Metric.POWER)

        TermCard("Temperature",
            "Heat is the biggest cause of battery wear. Keep the battery cool. Below 35°C is good. " +
                "Above 40°C for a long time shortens battery life.",
            Metric.TEMPERATURE)

        TermCard("What wears a battery",
            "Two things wear a battery. \"Cycle wear\" comes from charging and using it. \"Calendar wear\" comes from age and heat, " +
                "even when the phone is off. Cellspan shows both.")

        TermCard("Measured vs estimated",
            "Cellspan measures your real health after a few charges past 20%. Before that, it shows an estimate from one reading, " +
                "marked with a \"~\" sign. The estimate can move a little. The measured value is steady.")

        Spacer(Modifier.height(80.dp))
    }
}

/**
 * The privacy policy, shown in-app so it works with no internet connection.
 * Mirrors docs/privacy-policy.md in short, plain sentences.
 */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        InfoHeader("Privacy policy", onBack)
        Text("Cellspan reads your battery data and shows it to you. This page explains what happens to that data.",
            color = Cell.TextMuted, fontSize = 13.sp, lineHeight = 19.sp)

        Spacer(Modifier.height(4.dp))
        SurfaceCard(Modifier.padding(top = 12.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Cell.Good))
                    Text("Everything stays on your phone", color = Cell.TextPrimary, fontFamily = Sans,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Cellspan does not send your data to us or to any server. The app has no internet permission, " +
                    "so it cannot upload anything. There are no accounts, no ads, and no tracking.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }

        Para("What the app reads",
            "Charge level, status, temperature, voltage, current, and (on Android 14 and later) the cycle count. " +
                "It also reads your phone model to find the battery's rated capacity. All of this stays on the device.")

        Para("App screen time (optional)",
            "If you allow \"Usage access\", Cellspan estimates which apps used the screen most. This is only shown on screen. " +
                "It is not saved and not exported. You can turn it off any time in Android Settings.")

        Para("Where data is stored",
            "Data is kept in Cellspan's own private space on the phone. Other apps cannot read it. " +
                "If you uninstall Cellspan, all of its data is deleted.")

        Para("Exporting your data",
            "You can export your battery data to a file that you choose. The file has only numbers and timestamps. " +
                "It has no app usage and no personal details. After you save the file, what you do with it is your choice.")

        Para("Permissions",
            "Wi-Fi state: to show whether Wi-Fi is on, as a drain hint. " +
                "Usage access: optional, only for the app screen-time estimate. " +
                "Cellspan asks for no location, contacts, camera, microphone, storage, or internet.")

        Para("Contact",
            "Questions: jenilagrawal@gmail.com")

        Spacer(Modifier.height(80.dp))
    }
}

/** A tappable row that opens an info sub-screen (used on the Diagnose screen). */
@Composable
fun NavRow(label: String, sub: String, onClick: () -> Unit, metric: Metric? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (metric != null) MetricGlyph(metric, Cell.Accent, glyphSize = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(label, color = Cell.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Cell.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Text("›", color = Cell.TextFaint, fontFamily = Sans, fontSize = 20.sp)
    }
}
