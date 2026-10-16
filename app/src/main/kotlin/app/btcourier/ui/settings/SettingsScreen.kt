package app.btcourier.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.btcourier.R
import app.btcourier.data.AppSettings
import app.btcourier.data.SinkConfig
import app.btcourier.data.sinkkind.module
import app.btcourier.ui.BackButton
import app.btcourier.ui.Toggle
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEditSink: (id: String?) -> Unit,
) {
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
            if (state.sinks.isEmpty()) Text(stringResource(R.string.settings_sinks_empty))
            state.sinks.forEach { sink ->
                SinkRow(sink, onClick = { onEditSink(sink.id) }, onEnabled = { viewModel.setSinkEnabled(sink.id, it) })
            }
            OutlinedButton(onClick = { onEditSink(null) }) { Text(stringResource(R.string.settings_sink_add)) }

            HorizontalDivider()
            Heading(R.string.settings_network)
            Toggle(R.string.settings_cellular, R.string.settings_cellular_help, form.uploadOverCellular) { v ->
                viewModel.edit { it.copy(uploadOverCellular = v) }
            }
            Picker(
                R.string.settings_upload_delay,
                UploadDelay.entries,
                UploadDelay.nearest(form.maxBatchAgeMs),
                { it.label },
                help = R.string.settings_upload_delay_help,
            ) { v -> viewModel.edit { it.copy(maxBatchAgeMs = v.ms) } }
            Toggle(R.string.settings_periodic, R.string.settings_periodic_help, form.periodicSync) { v ->
                viewModel.edit { it.copy(periodicSync = v) }
            }
            CarPicker(form.carDeviceAddress) { address -> viewModel.edit { it.copy(carDeviceAddress = address) } }
            Toggle(R.string.settings_drive_session, R.string.settings_drive_session_help, form.driveSession) { v ->
                viewModel.edit { it.copy(driveSession = v) }
            }
            if (form.driveSession) {
                MinutesPicker(
                    R.string.settings_drive_interval,
                    R.string.settings_drive_interval_help,
                    DRIVE_INTERVAL_OPTIONS,
                    form.drivePullIntervalMinutes,
                ) { v -> viewModel.edit { it.copy(drivePullIntervalMinutes = v) } }
                MinutesPicker(
                    R.string.settings_drive_idle,
                    R.string.settings_drive_idle_help,
                    DRIVE_IDLE_OPTIONS,
                    form.driveIdleMinutes,
                ) { v -> viewModel.edit { it.copy(driveIdleMinutes = v) } }
                MinutesPicker(
                    R.string.settings_drive_max,
                    R.string.settings_drive_max_help,
                    DRIVE_MAX_OPTIONS,
                    form.driveMaxSessionMinutes,
                ) { v -> viewModel.edit { it.copy(driveMaxSessionMinutes = v) } }
            }

            HorizontalDivider()
            Heading(R.string.settings_cache)
            OutlinedTextField(
                value = form.cacheCapMb,
                onValueChange = { v -> viewModel.edit { it.copy(cacheCapMb = v) } },
                label = { Text(stringResource(R.string.settings_cache_cap)) },
                supportingText = {
                    val text =
                        when (state.capProblem) {
                            null -> {
                                stringResource(R.string.settings_cache_cap_help)
                            }

                            CacheCapProblem.NOT_A_NUMBER -> {
                                stringResource(R.string.settings_cache_cap_nan)
                            }

                            CacheCapProblem.OUT_OF_RANGE -> {
                                stringResource(
                                    R.string.settings_cache_cap_range,
                                    MIN_CACHE_CAP_MB.toString(),
                                    MAX_CACHE_CAP_MB.toString(),
                                )
                            }
                        }
                    Text(text)
                },
                isError = state.capProblem != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.loaded,
                ) { Text(stringResource(R.string.settings_save)) }
                if (state.saved) Text(stringResource(R.string.settings_saved))
            }
        }
    }
}

/** The upload delays offered, as [SettingsForm.maxBatchAgeMs]. */
private enum class UploadDelay(
    val ms: Long,
    @StringRes val label: Int,
) {
    AFTER_SYNC(0, R.string.settings_upload_delay_none),
    FIVE_MINUTES(5 * 60_000L, R.string.settings_upload_delay_5min),
    THIRTY_MINUTES(30 * 60_000L, R.string.settings_upload_delay_30min),
    ;

    companion object {
        /** The offered delay closest to a stored one, which only these choices ever write. */
        fun nearest(ms: Long) = entries.minBy { abs(it.ms - ms) }
    }
}

/** The drive session choices offered, in minutes, each within its [AppSettings] range. */
internal val DRIVE_INTERVAL_OPTIONS = listOf(1, 2, 5, 10)
internal val DRIVE_IDLE_OPTIONS = listOf(2, 5, 10, 15, 30, 60)
internal val DRIVE_MAX_OPTIONS = listOf(30, 60, 120, 180, 240, 480)

/** A [Picker] of minute counts; a stored value that is not offered is shown as it is. */
@Composable
private fun MinutesPicker(
    @StringRes label: Int,
    @StringRes help: Int,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) = Picker(
    label,
    (options + selected).distinct().sorted(),
    selected,
    help,
    textOf = { pluralStringResource(R.plurals.settings_minutes, it, it) },
    onSelect = onSelect,
)

@Composable
private fun SinkRow(
    sink: SinkConfig,
    onClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sink.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    R.string.status_two_values,
                    stringResource(sink.transport.kind.module.label),
                    stringResource(formatLabel(sink.format)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = sink.enabled, onCheckedChange = onEnabled)
    }
}

@Composable
internal fun Heading(
    @StringRes text: Int,
) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
}
