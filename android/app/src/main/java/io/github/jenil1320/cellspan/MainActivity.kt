package io.github.jenil1320.cellspan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jenil1320.cellspan.ui.BatteryAgeInfoScreen
import io.github.jenil1320.cellspan.ui.BatteryBasicsScreen
import io.github.jenil1320.cellspan.ui.HealthScreen
import io.github.jenil1320.cellspan.ui.HistoryScreen
import io.github.jenil1320.cellspan.ui.MainViewModel
import io.github.jenil1320.cellspan.ui.NowScreen
import io.github.jenil1320.cellspan.ui.OnboardingScreen
import io.github.jenil1320.cellspan.ui.SettingsScreen
import io.github.jenil1320.cellspan.ui.PrivacyPolicyScreen
import io.github.jenil1320.cellspan.ui.TrendsScreen
import io.github.jenil1320.cellspan.data.AppPrefs
import io.github.jenil1320.cellspan.data.BatteryGuard
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.CellspanTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            // The system bars are not Compose, so they are told separately — and they must
            // follow the palette. Hard-coding light icons meant a dark-mode phone drew a
            // dark clock on a dark bar, and the navigation bar kept a near-black colour
            // from the old palette even in light mode, leaving a black strip under a
            // near-white app.
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            SideEffect {
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    // From Android 15 the bars are always transparent and these are no-ops.
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
            }
            CellspanTheme(dark) { AppRoot() }
        }
    }
}

private enum class Tab(val label: String) {
    NOW("Now"), HEALTH("Health"), TRENDS("Trends"), HISTORY("History"), SETTINGS("Settings")
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    // Full-screen info page overlaid on top of the tabs ("basics" | "privacy" | null).
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    // First run: show the onboarding flow until the user finishes it.
    var onboarded by rememberSaveable { mutableStateOf(AppPrefs.isOnboarded(ctx)) }
    if (!onboarded) {
        OnboardingScreen(onDone = { AppPrefs.setOnboarded(ctx); onboarded = true })
        return
    }

    // Live pulse: refresh vitals + the SoC log ONLY while the app is foreground
    // (repeatOnLifecycle cancels the loop when backgrounded, so it never polls
    // in the background), and at a gentler 60s cadence to keep its own drain low.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Push updates: the battery broadcast fires the instant the level, status or
            // temperature moves, so the orb tracks the phone in real time. The 60s tick
            // stays as the persistence beat — the broadcast itself writes nothing.
            val live = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    vm.liveUpdate()
                    // Also the moment to start the guard if a threshold has just come into
                    // range — plugging in, or the level drifting down to the low reminder,
                    // while the app happens to be open. ensureRunning returns immediately
                    // when there is nothing to watch, so this costs nothing on the vast
                    // majority of broadcasts, and starting a foreground service is always
                    // permitted while the app is visible.
                    runCatching { BatteryGuard.ensureRunning(ctx) }
                }
            }
            ContextCompat.registerReceiver(
                ctx, live, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Heal the guard while the app is visible. This is the one moment Android
            // always allows a foreground service to be started, so it is the reliable
            // recovery path after an OEM task killer has taken the guard down — the
            // quarter-hourly watchdog can only manage it on an allowlisted phone.
            runCatching { BatteryGuard.ensureRunning(ctx) }
            try {
                // Tick first, then wait: a resume otherwise shows the previous
                // session's numbers for a full minute.
                while (true) {
                    vm.tick()
                    delay(60_000)
                }
            } finally {
                // repeatOnLifecycle cancels this block on STOP, so the receiver never
                // outlives the foreground.
                runCatching { ctx.unregisterReceiver(live) }
            }
        }
    }

    // An open info page takes over the whole screen (with a back control + system back).
    if (info != null) {
        BackHandler { info = null }
        Box(Modifier.fillMaxSize().background(Cell.pageBackground)) {
            when (info) {
                "basics" -> BatteryBasicsScreen(onBack = { info = null })
                "privacy" -> PrivacyPolicyScreen(onBack = { info = null })
                // Closing the page before jumping to Settings, so back does not land
                // the user on the explainer again after they set the date.
                "age" -> BatteryAgeInfoScreen(
                    onBack = { info = null },
                    onSetDate = { info = null; tab = Tab.SETTINGS },
                )
            }
        }
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        // safeDrawing (not just systemBars) keeps content clear of a display cutout in landscape.
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = { CellNavBar(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Cell.pageBackground).padding(padding)) {
            when (tab) {
                Tab.NOW -> NowScreen(state.snapshot, state.dailyUsage, state.socLog,
                    state.screenSplit, state.guardRunning)
                Tab.HEALTH -> HealthScreen(state.snapshot, state.analysis, state.history, state.sohForecast,
                    onOpenInfo = { info = it })
                Tab.TRENDS -> TrendsScreen(state.socLog, state.dailyVitals, state.dailyUsage)
                Tab.HISTORY -> HistoryScreen(state.sessions)
                Tab.SETTINGS -> SettingsScreen(
                    state.snapshot, state.analysis,
                    onOpenInfo = { info = it },
                    onSetDesignCapacity = { mah -> AppPrefs.setDesignOverrideMah(ctx, mah); vm.refresh() },
                    onSetBatteryStart = { ms -> AppPrefs.setBatteryStartMs(ctx, ms); vm.refresh() },
                    onImported = { vm.refresh() },
                )
            }
        }
    }
}

@Composable
private fun CellNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cell.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cell.Border))
        Row(
            // The Scaffold's insets never reach the bottomBar slot, so the bar pads the cutout itself.
            Modifier.fillMaxWidth().navigationBarsPadding().displayCutoutPadding()
                .padding(top = 10.dp, bottom = 10.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { t ->
                val active = t == current
                val c = if (active) Cell.Accent else Cell.TextFaint
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    // selectable, so TalkBack reads each tab as a tab and says which one is selected.
                    modifier = Modifier
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(t) })
                        // The dot plus the label came to roughly 42dp tall, under the 48dp
                        // minimum for a touch target. sizeIn raises the hit area without
                        // moving anything visually.
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .then(
                                if (active) Modifier.background(c)
                                else Modifier.border(1.5.dp, c, CircleShape)
                            )
                    )
                    Text(t.label, color = c, fontSize = 10.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}
