package io.github.jenil1320.cellspan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jenil1320.cellspan.ui.BatteryBasicsScreen
import io.github.jenil1320.cellspan.ui.DiagnoseScreen
import io.github.jenil1320.cellspan.ui.HealthScreen
import io.github.jenil1320.cellspan.ui.HistoryScreen
import io.github.jenil1320.cellspan.ui.MainViewModel
import io.github.jenil1320.cellspan.ui.NowScreen
import io.github.jenil1320.cellspan.ui.PrivacyPolicyScreen
import io.github.jenil1320.cellspan.ui.TrendsScreen
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.CellspanTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light theme → dark status-bar icons so the clock/battery stay legible.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent { CellspanTheme { AppRoot() } }
    }
}

private enum class Tab(val label: String) {
    NOW("Now"), HEALTH("Health"), TRENDS("Trends"), HISTORY("History"), DIAGNOSE("Diagnose")
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    // Full-screen info page overlaid on top of the tabs ("basics" | "privacy" | null).
    var info by rememberSaveable { mutableStateOf<String?>(null) }

    // Live pulse: refresh vitals + the SoC log ONLY while the app is foreground
    // (repeatOnLifecycle cancels the loop when backgrounded, so it never polls
    // in the background), and at a gentler 60s cadence to keep its own drain low.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000)
                vm.tick()
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
            }
        }
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { CellNavBar(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Cell.pageBackground).padding(padding)) {
            when (tab) {
                Tab.NOW -> NowScreen(state.snapshot, state.analysis, state.dailyUsage, state.socLog)
                Tab.HEALTH -> HealthScreen(state.snapshot, state.analysis, state.history, state.sohForecast)
                Tab.TRENDS -> TrendsScreen(state.socLog, state.dailyVitals, state.dailyUsage)
                Tab.HISTORY -> HistoryScreen(state.sessions)
                Tab.DIAGNOSE -> DiagnoseScreen(state.snapshot, state.analysis, onOpenInfo = { info = it })
            }
        }
    }
}

@Composable
private fun CellNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cell.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cell.Border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { t ->
                val active = t == current
                val c = if (active) Cell.Accent else Cell.TextFaint
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { onSelect(t) }.padding(8.dp),
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
