package app.btcourier.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/**
 * A titled on/off row with optional [help] under the title: a switch for a setting that applies
 * at once, or with [role] [Role.Checkbox] a checkbox for a choice a later step acts on.
 */
@Composable
internal fun Toggle(
    @StringRes title: Int,
    @StringRes help: Int?,
    checked: Boolean,
    role: Role = Role.Switch,
    onChange: (Boolean) -> Unit,
) {
    // The whole row is the control, so its title labels it for screen readers and the touch target.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = role, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (role == Role.Checkbox) Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (help != null) Text(stringResource(help), style = MaterialTheme.typography.bodySmall)
        }
        if (role != Role.Checkbox) Switch(checked = checked, onCheckedChange = null)
    }
}
