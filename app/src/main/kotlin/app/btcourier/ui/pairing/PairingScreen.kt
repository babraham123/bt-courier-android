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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.btcourier.AppGraph
import app.btcourier.R
import app.btcourier.ble.KableGattLink
import app.btcourier.ble.PairingException
import app.btcourier.ble.ProbeResult
import app.btcourier.ble.adapter
import app.btcourier.ble.associatedDevice
import app.btcourier.ble.bond
import app.btcourier.ble.hasBluetoothConnectPermission
import app.btcourier.ble.pairingStep
import app.btcourier.ble.probeStatus
import app.btcourier.ble.requestAssociation
import app.btcourier.work.DevicePresence
import kotlinx.coroutines.launch

private enum class Step { Explain, Associate, Bond, Test }

/**
 * Pair wizard: explain → companion-device association → bond (system passkey dialog) → test
 * connection over `ble_http`. The association is the persisted record of the paired device
 * (see `CompanionPairedDeviceRepository`), so finishing the wizard needs no extra save.
 */
@Composable
fun PairingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Saved across configuration changes: the system chooser and pairing dialog can outlive
    // the activity instance that started them.
    var step by rememberSaveable { mutableStateOf(Step.Explain) }
    var address by rememberSaveable { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf<BluetoothDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var bondAttempt by remember { mutableIntStateOf(0) }

    // Resolved during composition so they follow configuration (locale) changes.
    val noDeviceMessage = stringResource(R.string.pairing_error_no_device)
    val permissionMessage = stringResource(R.string.pairing_error_permission)
    val noCdmMessage = stringResource(R.string.pairing_error_no_cdm)

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        busy = false
        val picked = if (result.resultCode == Activity.RESULT_OK) associatedDevice(context, result.data) else null
        if (picked == null) {
            error = noDeviceMessage
        } else {
            device = picked
            address = picked.address
            error = null
            step = Step.Bond
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            error = null
            step = Step.Associate
        } else {
            error = permissionMessage
        }
    }

    fun currentDevice(): BluetoothDevice? =
        device ?: address?.let { addr -> adapter(context)?.getRemoteDevice(addr) }

    if (step == Step.Bond) {
        LaunchedEffect(address, bondAttempt) {
            val target = currentDevice() ?: return@LaunchedEffect
            busy = true
            error = null
            try {
                pairingStep("Bonding") { bondWithPermission(context, target, permissionMessage) }
                observePresence(context, target.address)
                step = Step.Test
            } catch (e: PairingException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            Step.Explain -> {
                Text(stringResource(R.string.pairing_explain_ble))
                Text(stringResource(R.string.pairing_explain_passkey))
                Text(stringResource(R.string.pairing_explain_window))
                Button(onClick = {
                    if (hasBluetoothConnectPermission(context)) {
                        step = Step.Associate
                    } else {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }) { Text(stringResource(R.string.pairing_start)) }
            }

            Step.Associate -> {
                Text(stringResource(R.string.pairing_associate_body))
                Button(enabled = !busy, onClick = {
                    val cdm = context.getSystemService(CompanionDeviceManager::class.java)
                    if (cdm == null) {
                        error = noCdmMessage
                        return@Button
                    }
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val sender = pairingStep("Scan") { cdm.requestAssociation(context) }
                            chooser.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (e: PairingException) {
                            busy = false
                            error = e.message
                        }
                    }
                }) { Text(stringResource(R.string.pairing_find)) }
            }

            Step.Bond -> {
                Text(stringResource(R.string.pairing_bond_body))
                if (!busy && error != null) {
                    Button(onClick = { bondAttempt++ }) { Text(stringResource(R.string.pairing_retry)) }
                }
            }

            Step.Test -> {
                Text(stringResource(R.string.pairing_test_body))
                probe?.let {
                    Text(stringResource(R.string.pairing_test_ok, it.firmwareVersion, it.mtu))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = {
                        val addr = address ?: return@Button
                        busy = true
                        error = null
                        probe = null
                        scope.launch {
                            try {
                                probe = pairingStep("Test connection") {
                                    val link = KableGattLink.connect(addr)
                                    try {
                                        probeStatus(link)
                                    } finally {
                                        link.close()
                                    }
                                }
                            } catch (e: PairingException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.pairing_test)) }
                    OutlinedButton(onClick = onDone) { Text(stringResource(R.string.pairing_done)) }
                }
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.pairing_working))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * From here on the system binds `PresenceService` whenever the dongle comes in range, which is
 * what starts background syncs. The association already exists (bonding comes after it), so a
 * false return means the user removed it meanwhile; the log says so instead of failing pairing.
 */
private fun observePresence(context: Context, address: String) {
    val observing = DevicePresence.startObserving(context.applicationContext, address)
    AppGraph.log.info("pairing", "bonded with $address; presence observed: $observing")
}

@SuppressLint("MissingPermission") // Checked just above; the wizard cannot reach this step without it.
private suspend fun bondWithPermission(context: Context, device: BluetoothDevice, missingPermission: String) {
    if (!hasBluetoothConnectPermission(context)) throw PairingException(missingPermission)
    bond(context, device)
}
