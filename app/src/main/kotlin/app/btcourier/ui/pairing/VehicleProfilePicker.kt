package app.btcourier.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.btcourier.R
import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.device.PairingChoiceText

/**
 * The car models of [models] whose name contains [query], ignoring case: the generic profile
 * first (also found by its display name [genericLabel]), then the rest in alphabetical order.
 */
internal fun vehicleProfileModels(
    query: String,
    genericLabel: String,
    models: List<String> = VehicleProfiles.all().map { it.carModel },
): List<String> {
    val q = query.trim()
    val matching =
        models.filter { model ->
            model.contains(q, ignoreCase = true) ||
                (model == VehicleProfiles.GENERIC_MODEL && genericLabel.contains(q, ignoreCase = true))
        }
    val (generic, rest) = matching.partition { it == VehicleProfiles.GENERIC_MODEL }
    return generic + rest.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/**
 * The explain step's vehicle profile choice: a search field over the shipped car models and the
 * matches as radio rows, [selected] (null meaning the generic profile) checked. The list scrolls
 * within a bounded height so the step's Start button stays near.
 */
@Composable
internal fun VehicleProfilePicker(
    choice: PairingChoiceText,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val genericLabel = stringResource(R.string.pairing_vehicle_profile_generic)
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query, genericLabel) { vehicleProfileModels(query, genericLabel) }
    val current = selected ?: VehicleProfiles.GENERIC_MODEL
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(choice.label), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(choice.help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.pairing_vehicle_profile_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isEmpty()) Text(stringResource(R.string.pairing_vehicle_profile_none))
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            matches.forEach { model ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = model == current, role = Role.RadioButton, onClick = { onSelect(model) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model == current, onClick = null)
                    Text(
                        if (model == VehicleProfiles.GENERIC_MODEL) genericLabel else model,
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
