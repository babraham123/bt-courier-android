package app.btcourier.ui.status

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.btcourier.R
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStopKind
import app.btcourier.data.UploadResultKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: StatusViewModel, onPair: () -> Unit, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Pairing, or removing the association in system settings, changes the device behind our back.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDevice()
        onPauseOrDispose {}
    }

    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(viewModel::saveExport)
    }
    val savedMessage = stringResource(R.string.status_export_saved)
    val failedMessage = stringResource(R.string.status_export_failed)
    LaunchedEffect(state.export) {
        val result = state.export ?: return@LaunchedEffect
        snackbar.showSnackbar(if (result == ExportResult.SAVED) savedMessage else failedMessage)
        viewModel.exportShown()
    }
    val shareTitle = stringResource(R.string.status_export_share_title)
    val exportFileName = stringResource(R.string.status_export_file_name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(R.string.status_device) {
                val device = state.device
                if (device == null) {
                    Text(stringResource(R.string.home_not_paired))
                    Button(onClick = onPair) { Text(stringResource(R.string.home_pair)) }
                } else {
                    device.displayName?.let { InfoRow(R.string.status_device_name, it) }
                    InfoRow(R.string.status_device_address, device.address)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::forget) { Text(stringResource(R.string.home_forget)) }
                        OutlinedButton(onClick = onPair) { Text(stringResource(R.string.status_repair)) }
                    }
                }
            }

            Section(R.string.status_sync) {
                val sync = state.lastSync
                if (sync == null) {
                    Text(stringResource(R.string.status_never))
                } else {
                    InfoRow(R.string.status_when, formatTime(sync.atMs))
                    InfoRow(R.string.status_result, stringResource(syncLabel(sync.result)))
                    if (sync.pages != null && sync.lines != null) {
                        InfoRow(R.string.status_pages_lines, stringResource(R.string.status_pages_lines_value, sync.pages, sync.lines))
                    }
                    sync.stoppedBecause?.let { InfoRow(R.string.status_stopped_because, stringResource(stopLabel(it))) }
                }
                InfoRow(R.string.status_cursor, state.cursor ?: stringResource(R.string.status_none))
                Button(onClick = viewModel::syncNow, enabled = state.device != null) { Text(stringResource(R.string.status_sync_now)) }
            }

            Section(R.string.status_cache) {
                InfoRow(R.string.status_pending_batches, state.pendingBatches.toString())
                InfoRow(
                    R.string.status_stored,
                    stringResource(R.string.status_of_cap, formatBytes(state.bytesStored), formatBytes(state.cacheCapBytes)),
                )
                InfoRow(
                    R.string.status_dropped,
                    stringResource(
                        R.string.status_two_values,
                        pluralStringResource(R.plurals.status_dropped_lines, state.droppedLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state.droppedLines),
                        formatBytes(state.droppedBytes),
                    ),
                    highlight = state.droppedLines > 0,
                )
            }

            Section(R.string.status_upload) {
                val upload = state.lastUpload
                if (upload == null) {
                    Text(stringResource(R.string.status_never))
                } else {
                    InfoRow(R.string.status_when, formatTime(upload.atMs))
                    InfoRow(R.string.status_result, stringResource(uploadLabel(upload.result)))
                }
                state.sinks.forEach { (id, sink) ->
                    InfoRow(
                        stringResource(R.string.status_sink, id),
                        stringResource(R.string.status_two_values, stringResource(uploadLabel(sink.result)), formatTime(sink.atMs)),
                    )
                }
                if (!state.sinkConfigured) Text(stringResource(R.string.status_no_sink_hint))
                Button(onClick = viewModel::uploadNow, enabled = state.sinkConfigured) {
                    Text(stringResource(R.string.status_upload_now))
                }
            }

            state.lastError?.let { error ->
                Section(R.string.status_last_error) {
                    error.atMs?.let { InfoRow(R.string.status_when, formatTime(it)) }
                    Text(error.message, color = MaterialTheme.colorScheme.error)
                }
            }

            Section(R.string.status_diagnostics) {
                Text(stringResource(R.string.status_diagnostics_body))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saveLog.launch(exportFileName) }) { Text(stringResource(R.string.status_export_save)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, exportFileName)
                                .putExtra(Intent.EXTRA_TEXT, viewModel.exportText())
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }
                    }) { Text(stringResource(R.string.status_export_share)) }
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String, highlight: Boolean = false) = InfoRow(stringResource(label), value, highlight)

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = if (highlight) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

@Composable
private fun formatTime(ms: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context,
        ms,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
}

@Composable
private fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@StringRes
private fun syncLabel(kind: SyncResultKind): Int = when (kind) {
    SyncResultKind.CAUGHT_UP -> R.string.sync_caught_up
    SyncResultKind.INTERRUPTED -> R.string.sync_interrupted
    SyncResultKind.DEVICE_ERROR -> R.string.sync_device_error
    SyncResultKind.UNREACHABLE -> R.string.sync_unreachable
    SyncResultKind.NOT_PAIRED -> R.string.sync_not_paired
}

@StringRes
private fun stopLabel(kind: SyncStopKind): Int = when (kind) {
    SyncStopKind.CAUGHT_UP -> R.string.stop_caught_up
    SyncStopKind.NO_PROGRESS -> R.string.stop_no_progress
    SyncStopKind.LINK_LOST -> R.string.stop_link_lost
    SyncStopKind.TRUNCATED -> R.string.stop_truncated
    SyncStopKind.BUSY -> R.string.stop_busy
    SyncStopKind.DEVICE_ERROR -> R.string.stop_device_error
}

@StringRes
private fun uploadLabel(kind: UploadResultKind): Int = when (kind) {
    UploadResultKind.DELIVERED -> R.string.upload_delivered
    UploadResultKind.RETRYING -> R.string.upload_retrying
    UploadResultKind.GAVE_UP -> R.string.upload_gave_up
    UploadResultKind.FAILED -> R.string.upload_failed
    UploadResultKind.NO_SINK -> R.string.upload_no_sink
}
