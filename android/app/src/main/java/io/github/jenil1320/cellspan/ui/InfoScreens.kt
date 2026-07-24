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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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
            // clickable adds no minimum size, so hold the 48dp accessible touch target ourselves.
            Modifier.clip(CircleShape)
                .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .padding(8.dp),
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
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
            "Cellspan measures your real health after you charge from 60% or below all the way to full a few times. " +
                "Before that, it shows an estimate from one reading, " +
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
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
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
                "It also reads your phone model to find the battery's rated capacity. " +
                "For the drain hints it reads whether Wi-Fi, Bluetooth, location services, battery saver and airplane mode are on or off, " +
                "and the screen brightness level. These are on/off states only. " +
                "Cellspan never reads your position, and it holds no coordinates or other location data. " +
                "All of this stays on the device.")

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
                "Notifications: optional, only for the charge alarm. On Android 13 and later the app asks for it when you set the alarm. " +
                "Usage access: optional, only for the app screen-time estimate. " +
                "Cellspan asks for no location, contacts, camera, microphone, storage, or internet.")

        Para("Contact",
            "Questions: jenilagrawal@gmail.com")

        Spacer(Modifier.height(80.dp))
    }
}

/** A tappable row that opens an info sub-screen (used on the Diagnose screen). */
/** One numbered step, so the instructions read as a sequence rather than a wall of prose. */
@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Cell.AccentSoft),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Cell.Accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
        Column {
            Text(title, color = Cell.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
            Text(body, color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** A copyable command block — an ADB line is useless if it can't be copied accurately. */
@Composable
private fun CommandBlock(command: String) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
            .background(Cell.TileCharcoal).padding(14.dp),
    ) {
        Text(command, color = Cell.OnTile, fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Copy", color = Cell.AccentLight, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable {
                    clipboard.setText(AnnotatedString(command))
                    Toast.makeText(ctx, "Command copied", Toast.LENGTH_SHORT).show()
                }
                .padding(top = 10.dp, bottom = 4.dp))
    }
}

/**
 * How to give Cellspan the battery's age. Android hides the manufacture date from ordinary
 * apps, so this is the one number the app may have to ask for — and without it the wear
 * model has to drop its age-and-heat term entirely. Two routes, easy one first.
 */
@Composable
fun BatteryAgeInfoScreen(onBack: () -> Unit, onSetDate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        InfoHeader("Battery age", onBack)
        Text(
            "A battery wears in two ways: from charging, and from simply getting older and hot. " +
                "To separate the two, Cellspan needs to know how old the cell is. Android does not let " +
                "ordinary apps read the date it was made, so there are two ways to supply it.",
            color = Cell.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        SurfaceCard {
            Column {
                SectionLabel("Option 1 · tell Cellspan", color = Cell.Accent)
                Text("Roughly when you got the phone is enough. Being a month out changes the answer by well under one percent.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp))
                Text("Set the date now", color = Cell.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onSetDate() }.padding(vertical = 15.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("Option 2 · read it from the battery")
                Text(
                    "The exact date is stored in the battery itself. One command from a computer unlocks it, " +
                        "and Cellspan then fills the date in on its own. This needs no root and does not " +
                        "modify your phone — it only grants Cellspan permission to read a value it is " +
                        "otherwise blocked from seeing. It lasts until you uninstall the app.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Step(1, "Install ADB on a computer",
                    "ADB is part of Google's free Android Platform Tools. Search for “Android Platform Tools”, download it and unzip it.")
                Step(2, "Turn on USB debugging",
                    "On the phone open Settings, then About phone, and tap “Build number” seven times. Go back, open Developer options, and switch on USB debugging.")
                Step(3, "Connect the phone and run this",
                    "Plug the phone into the computer, accept the “Allow USB debugging?” prompt, then run:")
                CommandBlock("adb shell pm grant io.github.jenil1320.cellspan android.permission.BATTERY_STATS")
                Step(4, "Reopen Cellspan",
                    "The battery age appears by itself, marked “from battery”, and the age-and-heat share is calculated from the real date.")
                Text(
                    "You can switch USB debugging back off afterwards; the permission stays. " +
                        "If the command prints nothing, it worked — ADB is quiet on success.",
                    color = Cell.TextFaint, fontSize = 11.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SurfaceCard {
            Column {
                SectionLabel("If you skip both")
                Text(
                    "Cellspan still measures your State of Health and still shows the wear from charging. " +
                        "The only thing missing is the split between charging and age, because that share " +
                        "cannot be worked out without a date. Nothing else is affected.",
                    color = Cell.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

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
