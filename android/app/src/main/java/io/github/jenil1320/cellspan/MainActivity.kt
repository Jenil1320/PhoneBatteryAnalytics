package io.github.jenil1320.cellspan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jenil1320.cellspan.ui.DiagnoseScreen
import io.github.jenil1320.cellspan.ui.HealthScreen
import io.github.jenil1320.cellspan.ui.MainViewModel
import io.github.jenil1320.cellspan.ui.TrendsScreen
import io.github.jenil1320.cellspan.ui.theme.Cell
import io.github.jenil1320.cellspan.ui.theme.CellspanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CellspanTheme { AppRoot() } }
    }
}

private enum class Tab(val label: String) {
    HEALTH("Health"), TRENDS("Trends"), DIAGNOSE("Diagnose")
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HEALTH) }

    Scaffold(
        containerColor = Cell.Bg,
        bottomBar = { CellNavBar(tab) { tab = it } },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.HEALTH -> HealthScreen(state.snapshot, state.analysis, state.history)
                Tab.TRENDS -> TrendsScreen(state.analysis, state.history)
                Tab.DIAGNOSE -> DiagnoseScreen(state.snapshot, state.analysis)
            }
        }
    }
}

@Composable
private fun CellNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Cell.Bg)
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val active = t == current
            val c = if (active) Cell.Accent else Cell.TextFaint
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
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
                Text(t.label, color = c, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
