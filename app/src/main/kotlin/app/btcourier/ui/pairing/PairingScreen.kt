package app.btcourier.ui.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.btcourier.AppGraph
import app.btcourier.R
import app.btcourier.ble.KableGattLink
import app.btcourier.ble.PairingException
import app.btcourier.ble.ProbeResult
import app.btcourier.ble.adapter
import app.btcourier.ble.associatedDevice
import app.btcourier.ble.bond
import app.btcourier.ble.filterSpec
import app.btcourier.ble.gattProfile
import app.btcourier.ble.hasBluetoothConnectPermission
import app.btcourier.ble.pairingStep
import app.btcourier.ble.requestAssociation
import app.btcourier.data.DeviceKind
import app.btcourier.ui.status.kindLabel
import app.btcourier.work.DevicePresence
import kotlinx.coroutines.launch

/**
 * Pair wizard screen over [PairingViewModel]. It runs what needs an activity (the permission
 * request, the companion chooser, the bond dialog) and reports their outcomes to the view model.
 */
@Composable
fun PairingScreen(viewModel: PairingViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The scanned device carries the address type the stack saw, which bonding an RPA needs; it
    // cannot be saved, so after a process death bonding falls back to the address.
    var device by remember { mutableStateOf<BluetoothDevice?>(null) }

    // Resolved during composition so they follow configuration (locale) changes.
    val noDeviceMessage = stringResource(R.string.pairing_error_no_device)
    val permissionMessage = stringResource(R.string.pairing_error_permission)
    val noCdmMessage = stringResource(R.string.pairing_error_no_cdm)

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val picked = if (result.resultCode == Activity.RESULT_OK) associatedDevice(context, result.data) else null
        if (picked == null) {
            viewModel.failed(noDeviceMessage)
        } else {
            device = picked
            viewModel.associated(picked.address, advertisedName(context, picked))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.permissionGranted() else viewModel.failed(permissionMessage)
    }

    fun currentDevice(): BluetoothDevice? =
        device?.takeIf { it.address.equals(state.address, ignoreCase = true) }
            ?: state.address?.let { addr -> adapter(context)?.getRemoteDevice(addr) }

    if (state.step == PairingStep.Bond) {
        LaunchedEffect(state.address, state.bondAttempt) {
            val target = currentDevice() ?: return@LaunchedEffect
            viewModel.bonding()
            try {
                pairingStep("Bonding") { bondWithPermission(context, target, permissionMessage) }
                viewModel.bonded()
            } catch (e: PairingException) {
                viewModel.failed(e.message)
            }
        }
    }

    val ring = state.kind == DeviceKind.COLMI_RING
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            PairingStep.ChooseKind -> {
                Text(stringResource(R.string.pairing_choose_kind))
                DeviceKind.entries.forEach { kind ->
                    OutlinedButton(onClick = { viewModel.chooseKind(kind) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(kindHelp(kind)), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            PairingStep.Explain -> {
                if (ring) {
                    Text(stringResource(R.string.pairing_explain_ring))
                } else {
                    Text(stringResource(R.string.pairing_explain_ble))
                    Text(stringResource(R.string.pairing_explain_passkey))
                    Text(stringResource(R.string.pairing_explain_window))
                }
                Button(onClick = {
                    if (hasBluetoothConnectPermission(context)) {
                        viewModel.permissionGranted()
                    } else {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }) { Text(stringResource(R.string.pairing_start)) }
            }

            PairingStep.Associate -> {
                Text(stringResource(if (ring) R.string.pairing_associate_body_ring else R.string.pairing_associate_body))
                Button(enabled = !state.busy, onClick = {
                    val cdm = context.getSystemService(CompanionDeviceManager::class.java)
                    if (cdm == null) {
                        viewModel.failed(noCdmMessage)
                        return@Button
                    }
                    viewModel.scanning()
                    scope.launch {
                        try {
                            val sender = pairingStep("Scan") { cdm.requestAssociation(context, state.kind.filterSpec) }
                            chooser.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (e: PairingException) {
                            viewModel.failed(e.message)
                        }
                    }
                }) { Text(stringResource(R.string.pairing_find)) }
            }

            PairingStep.Bond -> {
                Text(stringResource(R.string.pairing_bond_body))
                if (!state.busy && state.error != null) {
                    Button(onClick = viewModel::retryBond) { Text(stringResource(R.string.pairing_retry)) }
                }
            }

            PairingStep.Test -> {
                Text(stringResource(if (ring) R.string.pairing_test_body_ring else R.string.pairing_test_body))
                when (val probe = state.probe) {
                    is ProbeResult.Wican -> Text(stringResource(R.string.pairing_test_ok, probe.firmwareVersion, probe.mtu))
                    is ProbeResult.Ring -> Text(stringResource(R.string.pairing_test_ok_ring, probe.battery.level))
                    null -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !state.busy, onClick = viewModel::test) { Text(stringResource(R.string.pairing_test)) }
                    OutlinedButton(onClick = onDone) { Text(stringResource(R.string.pairing_done)) }
                }
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.pairing_working))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@StringRes
private fun kindHelp(kind: DeviceKind): Int = when (kind) {
    DeviceKind.WICAN -> R.string.pairing_kind_wican_help
    DeviceKind.COLMI_RING -> R.string.pairing_kind_colmi_ring_help
}

/** The name the device advertised, if Bluetooth access allows reading it. */
@SuppressLint("MissingPermission") // Checked first; the wizard cannot reach the chooser without it.
private fun advertisedName(context: Context, device: BluetoothDevice): String? =
    if (hasBluetoothConnectPermission(context)) device.name?.takeIf { it.isNotBlank() } else null

/** [PairingPlatform] over CDM presence and [KableGattLink]. */
class AndroidPairingPlatform(context: Context) : PairingPlatform {
    private val appContext = context.applicationContext

    override fun observePresence(address: String): Boolean {
        val observing = DevicePresence.startObserving(appContext, address)
        AppGraph.log.info("pairing", "paired; presence observed: $observing")
        return observing
    }

    override suspend fun probe(address: String, kind: DeviceKind): ProbeResult {
        val link = KableGattLink.connect(address, kind.gattProfile)
        try {
            return app.btcourier.ble.probe(link, kind)
        } finally {
            link.close()
        }
    }
}

@SuppressLint("MissingPermission") // Checked just above; the wizard cannot reach this step without it.
private suspend fun bondWithPermission(context: Context, device: BluetoothDevice, missingPermission: String) {
    if (!hasBluetoothConnectPermission(context)) throw PairingException(missingPermission)
    bond(context, device)
}
