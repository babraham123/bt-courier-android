package app.btcourier.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.btcourier.R
import app.btcourier.ble.hasBluetoothConnectPermission
import app.btcourier.work.BondedDevices

/** Picks the car head unit for `CarTrigger` from the phone's bonded devices; null is "None". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarPicker(selected: String?, onSelect: (String?) -> Unit) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(hasBluetoothConnectPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    val bonded = remember(permitted) { if (permitted) BondedDevices.list(context) else emptyList() }
    // A car unpaired since it was chosen, or not listable without the permission, stays selectable
    // so the stored choice is shown rather than silently replaced.
    val devices = if (selected == null || bonded.any { it.second.equals(selected, ignoreCase = true) }) {
        bonded
    } else {
        bonded + (selected to selected)
    }
    val noneLabel = stringResource(R.string.settings_car_none)
    val selectedLabel = devices.firstOrNull { it.second.equals(selected, ignoreCase = true) }?.first ?: noneLabel
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_car)) },
            supportingText = { Text(stringResource(R.string.settings_car_help)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            devices.forEach { (name, address) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name)
                            if (name != address) Text(address, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        onSelect(address)
                        expanded = false
                    },
                )
            }
        }
    }
    if (!permitted) {
        TextButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
            Text(stringResource(R.string.settings_car_permission))
        }
    }
}
