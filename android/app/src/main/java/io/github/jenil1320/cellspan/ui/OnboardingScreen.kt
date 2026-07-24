package io.github.jenil1320.cellspan.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.Sans
import kotlinx.coroutines.launch

private data class OnboardPage(
    val metric: Metric,
    val title: String,
    val body: String,
    val usageGrant: Boolean = false,
)

private val ONBOARD_PAGES = listOf(
    OnboardPage(Metric.HEALTH, "Welcome to Cellspan",
        "See your battery's health, wear, and daily use — all worked out on your phone."),
    OnboardPage(Metric.SOC, "Your data stays private",
        "Cellspan has no internet permission. Nothing leaves your phone. No accounts, no ads, no tracking."),
    OnboardPage(Metric.CYCLES, "It learns as you charge",
        "Charge from 60% or below all the way to full a few times. Cellspan then measures your real battery health, not just an estimate."),
    OnboardPage(Metric.CURRENT, "See what uses the screen",
        "You can allow \"Usage access\" so Cellspan can rank apps by screen time. This is optional and stays on your phone.",
        usageGrant = true),
)

/** First-run onboarding: a few swipeable pages, then "Get started". */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { ONBOARD_PAGES.size })
    val lastIndex = ONBOARD_PAGES.size - 1

    Column(
        Modifier.fillMaxSize().background(Cell.pageBackground).statusBarsPadding()
            .navigationBarsPadding().padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val p = ONBOARD_PAGES[page]
            // Short pages stay centred; when the page is taller than the viewport
            // (landscape, tablets, split screen) it scrolls instead of clipping.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight).padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(120.dp).clip(CircleShape).background(Cell.AccentSoft),
                        contentAlignment = Alignment.Center,
                    ) { MetricGlyph(p.metric, Cell.Accent, glyphSize = 56.dp) }
                    Spacer(Modifier.height(28.dp))
                    Text(p.title, color = Cell.TextPrimary, fontFamily = Sans, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(p.body, color = Cell.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp,
                        textAlign = TextAlign.Center)
                    if (p.usageGrant) {
                        Spacer(Modifier.height(20.dp))
                        PillButton("Grant usage access", filled = false) {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                }
            }
        }

        // Page indicator dots.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(ONBOARD_PAGES.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    Modifier.padding(horizontal = 4.dp)
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (active) Cell.Accent else Cell.BorderHi),
                )
            }
        }

        // Bottom actions.
        if (pagerState.currentPage == lastIndex) {
            PillButton("Get started", filled = true, modifier = Modifier.fillMaxWidth(), onClick = onDone)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Skip", color = Cell.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(12.dp))
                Spacer(Modifier.weight(1f))
                PillButton("Next", filled = true) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) Cell.Accent else Cell.AccentSoft)
            .then(if (filled) Modifier else Modifier.border(1.dp, Cell.BorderAccent, RoundedCornerShape(12.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Cell.OnTile else Cell.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
