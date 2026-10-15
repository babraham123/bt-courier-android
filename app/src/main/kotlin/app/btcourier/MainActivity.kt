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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.btcourier.ui.BackButton
import app.btcourier.ui.SchedulerWorkControl
import app.btcourier.ui.VisitStores
import app.btcourier.ui.VisitStoresHolder
import app.btcourier.ui.pairing.AndroidPairingPlatform
import app.btcourier.ui.pairing.PairingScreen
import app.btcourier.ui.pairing.PairingViewModel
import app.btcourier.ui.settings.SettingsScreen
import app.btcourier.ui.settings.SettingsViewModel
import app.btcourier.ui.settings.SinkEditorScreen
import app.btcourier.ui.settings.SinkEditorViewModel
import app.btcourier.ui.status.AndroidDiagnostics
import app.btcourier.ui.status.StatusScreen
import app.btcourier.ui.status.StatusViewModel
import app.btcourier.ui.theme.BtCourierTheme
import app.btcourier.work.Notifications
import java.util.UUID

/** Four screens and a shallow back stack, so plain state replaces a navigation library. */
private enum class Screen { Status, Pairing, Settings, SinkEditor }

class MainActivity : ComponentActivity() {
    private val visitStores: VisitStores by lazy { ViewModelProvider(this)[VisitStoresHolder::class.java].stores }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.requestPermission(this)
        enableEdgeToEdge()
        setContent {
            BtCourierTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Status) }
                // The sink being edited, null for a new one, and a key that is fresh on every visit
                // so the editor never resumes a view model left in its saved or deleted state.
                // Each visit's view models live in their own store (visitStores), kept across
                // configuration changes and cleared once navigation leaves the visit.
                var editingSink by rememberSaveable { mutableStateOf<String?>(null) }
                var editorVisit by rememberSaveable { mutableStateOf("") }
                // Likewise for the pair wizard, so a second pairing starts at the first step.
                var pairingVisit by rememberSaveable { mutableStateOf("") }
                val home = { screen = Screen.Status }
                val toSettings = { screen = Screen.Settings }
                // The editor handles back itself, to drop what a never-saved sink stored.
                if (screen == Screen.Pairing || screen == Screen.Settings) BackHandler(onBack = home)
                val activeVisit = when (screen) {
                    Screen.Pairing -> pairingVisit
                    Screen.SinkEditor -> editorVisit
                    Screen.Status, Screen.Settings -> null
                }
                LaunchedEffect(activeVisit) { visitStores.keepOnly(activeVisit) }

                when (screen) {
                    Screen.Status -> StatusScreen(
                        viewModel = statusViewModel(),
                        onPair = {
                            pairingVisit = UUID.randomUUID().toString()
                            screen = Screen.Pairing
                        },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.Pairing -> PairingRoute(pairingViewModel(pairingVisit), onBack = home)
                    Screen.Settings -> SettingsScreen(viewModel = settingsViewModel(), onBack = home, onEditSink = { id ->
                        editingSink = id
                        editorVisit = UUID.randomUUID().toString()
                        screen = Screen.SinkEditor
                    })
                    Screen.SinkEditor -> SinkEditorScreen(viewModel = sinkEditorViewModel(editingSink, editorVisit), onDone = toSettings)
                }
            }
        }
    }

    @Composable
    private fun statusViewModel(): StatusViewModel = viewModel {
        StatusViewModel(
            status = AppGraph.status.status,
            settings = AppGraph.settings.settings,
            devices = AppGraph.pairedDevices,
            work = SchedulerWorkControl(applicationContext),
            diagnostics = AndroidDiagnostics(applicationContext, AppGraph.log, AppGraph.storeDir),
        )
    }

    @Composable
    private fun pairingViewModel(visit: String): PairingViewModel = viewModel(visitStores.owner(visit)) {
        PairingViewModel(AppGraph.pairedDevices, AndroidPairingPlatform(applicationContext))
    }

    @Composable
    private fun settingsViewModel(): SettingsViewModel = viewModel {
        SettingsViewModel(AppGraph.settings, SchedulerWorkControl(applicationContext))
    }

    @Composable
    private fun sinkEditorViewModel(sinkId: String?, visit: String): SinkEditorViewModel = viewModel(visitStores.owner(visit)) {
        SinkEditorViewModel(
            sinkId = sinkId,
            settings = AppGraph.settings,
            secrets = AppGraph.secrets,
            work = SchedulerWorkControl(applicationContext),
            sshKeys = AppGraph.sshKeys,
            http = AppGraph.httpClient,
            appScope = AppGraph.scope,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingRoute(viewModel: PairingViewModel, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.pairing_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        PairingScreen(viewModel, onDone = onBack, Modifier.padding(padding))
    }
}
