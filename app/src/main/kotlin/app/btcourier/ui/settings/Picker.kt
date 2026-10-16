package app.btcourier.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** A dropdown labelled [label] choosing one of [options], each named by the string [labelOf] gives. */
@Composable
internal fun <T> Picker(
    @StringRes label: Int,
    options: List<T>,
    selected: T,
    labelOf: (T) -> Int,
    @StringRes help: Int? = null,
    onSelect: (T) -> Unit,
) = Picker(label, options, selected, help, textOf = { stringResource(labelOf(it)) }, onSelect = onSelect)

/** A dropdown labelled [label] choosing one of [options], each named by the text [textOf] gives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Picker(
    @StringRes label: Int,
    options: List<T>,
    selected: T,
    @StringRes help: Int? = null,
    textOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = textOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(label)) },
            supportingText = help?.let { { Text(stringResource(it)) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(textOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
