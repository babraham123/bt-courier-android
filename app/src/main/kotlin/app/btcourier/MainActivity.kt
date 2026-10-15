package app.btcourier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.btcourier.ble.CompanionPairedDeviceRepository
import app.btcourier.ui.BackButton
import app.btcourier.ui.SchedulerWorkControl
import app.btcourier.ui.pairing.PairingScreen
import app.btcourier.ui.settings.SettingsScreen
import app.btcourier.ui.settings.SettingsViewModel
import app.btcourier.ui.status.AndroidDiagnostics
import app.btcourier.ui.status.StatusScreen
import app.btcourier.ui.status.StatusViewModel
import app.btcourier.ui.theme.BtCourierTheme
import app.btcourier.work.Notifications

/** Three screens and a back stack one deep, so plain state replaces a navigation library. */
private enum class Screen { Status, Pairing, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.requestPermission(this)
        enableEdgeToEdge()
        setContent {
            BtCourierTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Status) }
                val home = { screen = Screen.Status }
                if (screen != Screen.Status) BackHandler(onBack = home)

                when (screen) {
                    Screen.Status -> StatusScreen(
                        viewModel = statusViewModel(),
                        onPair = { screen = Screen.Pairing },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.Pairing -> PairingRoute(onBack = home)
                    Screen.Settings -> SettingsScreen(viewModel = settingsViewModel(), onBack = home)
                }
            }
        }
    }

    @Composable
    private fun statusViewModel(): StatusViewModel = viewModel {
        StatusViewModel(
            status = AppGraph.status.status,
            settings = AppGraph.settings.settings,
            devices = CompanionPairedDeviceRepository(applicationContext),
            work = SchedulerWorkControl(applicationContext),
            diagnostics = AndroidDiagnostics(applicationContext, AppGraph.log, AppGraph.storeDir),
        )
    }

    @Composable
    private fun settingsViewModel(): SettingsViewModel = viewModel {
        SettingsViewModel(AppGraph.settings, AppGraph.secrets, SchedulerWorkControl(applicationContext))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingRoute(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.pairing_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        PairingScreen(onDone = onBack, Modifier.padding(padding))
    }
}
