package app.btcourier.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.btcourier.R
import app.btcourier.data.SinkAuth
import app.btcourier.ui.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Each visit starts from what is stored, not from edits abandoned on a previous visit.
    LaunchedEffect(Unit) { viewModel.reload() }
    val form = state.form

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(R.string.settings_sink)
            OutlinedTextField(
                value = form.sinkUrl,
                onValueChange = { v -> viewModel.edit { it.copy(sinkUrl = v) } },
                label = { Text(stringResource(R.string.settings_sink_url)) },
                placeholder = { Text(stringResource(R.string.settings_sink_url_placeholder)) },
                supportingText = { Text(stringResource(state.urlProblem?.let(::urlProblemText) ?: R.string.settings_sink_url_help)) },
                isError = state.urlProblem != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.settings_auth), style = MaterialTheme.typography.labelLarge)
            val modes = SinkAuth.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = form.sinkAuth == mode,
                        onClick = { viewModel.edit { it.copy(sinkAuth = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(stringResource(authLabel(mode))) }
                }
            }
            if (form.sinkAuth == SinkAuth.BASIC) {
                OutlinedTextField(
                    value = form.sinkUser,
                    onValueChange = { v -> viewModel.edit { it.copy(sinkUser = v) } },
                    label = { Text(stringResource(R.string.settings_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (form.sinkAuth != SinkAuth.NONE) {
                val secretLabel = if (form.sinkAuth == SinkAuth.BASIC) R.string.settings_password else R.string.settings_token
                OutlinedTextField(
                    value = form.secretInput,
                    onValueChange = { v -> viewModel.edit { it.copy(secretInput = v) } },
                    label = { Text(stringResource(secretLabel)) },
                    placeholder = {
                        if (state.hasStoredSecret) Text(stringResource(R.string.settings_secret_stored))
                    },
                    supportingText = { Text(stringResource(R.string.settings_secret_help)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.hasStoredSecret) {
                    TextButton(onClick = viewModel::clearSecret) { Text(stringResource(R.string.settings_secret_clear)) }
                }
            }
            Toggle(R.string.settings_compress, R.string.settings_compress_help, form.sinkCompress) { v ->
                viewModel.edit { it.copy(sinkCompress = v) }
            }

            HorizontalDivider()
            Heading(R.string.settings_network)
            Toggle(R.string.settings_cellular, R.string.settings_cellular_help, form.uploadOverCellular) { v ->
                viewModel.edit { it.copy(uploadOverCellular = v) }
            }
            Toggle(R.string.settings_periodic, R.string.settings_periodic_help, form.periodicSync) { v ->
                viewModel.edit { it.copy(periodicSync = v) }
            }
            CarPicker(form.carDeviceAddress) { address -> viewModel.edit { it.copy(carDeviceAddress = address) } }

            HorizontalDivider()
            Heading(R.string.settings_cache)
            OutlinedTextField(
                value = form.cacheCapMb,
                onValueChange = { v -> viewModel.edit { it.copy(cacheCapMb = v) } },
                label = { Text(stringResource(R.string.settings_cache_cap)) },
                supportingText = {
                    val text = when (state.capProblem) {
                        null -> stringResource(R.string.settings_cache_cap_help)
                        CacheCapProblem.NOT_A_NUMBER -> stringResource(R.string.settings_cache_cap_nan)
                        CacheCapProblem.OUT_OF_RANGE ->
                            stringResource(R.string.settings_cache_cap_range, MIN_CACHE_CAP_MB.toString(), MAX_CACHE_CAP_MB.toString())
                    }
                    Text(text)
                },
                isError = state.capProblem != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save, enabled = state.loaded) { Text(stringResource(R.string.settings_save)) }
                if (state.saved) Text(stringResource(R.string.settings_saved))
            }
        }
    }
}

@Composable
private fun Heading(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Toggle(@StringRes title: Int, @StringRes help: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@StringRes
private fun urlProblemText(problem: SinkUrlProblem): Int = when (problem) {
    SinkUrlProblem.INVALID -> R.string.settings_url_invalid
    SinkUrlProblem.CREDENTIALS_IN_URL -> R.string.settings_url_credentials
    SinkUrlProblem.QUERY_OR_FRAGMENT -> R.string.settings_url_query
    SinkUrlProblem.CLEARTEXT -> R.string.settings_url_cleartext
}

@StringRes
private fun authLabel(mode: SinkAuth): Int = when (mode) {
    SinkAuth.NONE -> R.string.settings_auth_none
    SinkAuth.BASIC -> R.string.settings_auth_basic
    SinkAuth.BEARER -> R.string.settings_auth_bearer
}
