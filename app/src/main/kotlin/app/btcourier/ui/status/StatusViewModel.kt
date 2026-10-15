package app.btcourier.ui.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.data.AppSettings
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatus
import app.btcourier.data.SyncStopKind
import app.btcourier.data.UploadResultKind
import app.btcourier.data.configuredSinkIds
import app.btcourier.ui.WorkControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

data class LastSync(
    val atMs: Long,
    val result: SyncResultKind,
    /** Null when the sync never reached the device (not paired, unreachable). */
    val pages: Int?,
    val lines: Long?,
    val stoppedBecause: SyncStopKind?,
)

data class LastUpload(val atMs: Long, val result: UploadResultKind)

data class LastError(val message: String, val atMs: Long?)

data class StatusUiState(
    val loading: Boolean = true,
    val device: PairedDevice? = null,
    val lastSync: LastSync? = null,
    val cursor: String? = null,
    val pendingBatches: Int = 0,
    val bytesStored: Long = 0,
    val cacheCapBytes: Long = AppSettings().cacheCapBytes,
    val droppedLines: Long = 0,
    val droppedBytes: Long = 0,
    val sinkConfigured: Boolean = false,
    val lastUpload: LastUpload? = null,
    /** Sink id to its last outcome, sorted by id. */
    val sinks: List<Pair<String, SinkUpload>> = emptyList(),
    val lastError: LastError? = null,
    val export: ExportResult? = null,
)

enum class ExportResult { SAVED, FAILED }

/** Builds and writes the diagnostics export; the Android side lives in `AndroidDiagnostics`. */
interface DiagnosticsExport {
    suspend fun text(): String

    suspend fun save(target: Uri, text: String)
}

/** Pure mapping from the persisted sources to what the status screen shows. */
fun statusUiState(status: SyncStatus, settings: AppSettings, device: PairedDevice?): StatusUiState {
    val reachedDevice = status.lastSync in setOf(SyncResultKind.CAUGHT_UP, SyncResultKind.INTERRUPTED, SyncResultKind.DEVICE_ERROR)
    val lastSync = status.lastSync?.let { result ->
        LastSync(
            atMs = status.lastSyncAtMs ?: 0,
            result = result,
            pages = status.lastSyncPages.takeIf { reachedDevice },
            lines = status.lastSyncLinesStored.takeIf { reachedDevice },
            stoppedBecause = status.lastSyncStop.takeIf { reachedDevice },
        )
    }
    return StatusUiState(
        loading = false,
        device = device,
        lastSync = lastSync,
        cursor = status.cursor,
        pendingBatches = status.pendingBatches,
        bytesStored = status.bytesStored,
        cacheCapBytes = settings.cacheCapBytes,
        droppedLines = status.droppedLines,
        droppedBytes = status.droppedBytes,
        sinkConfigured = configuredSinkIds(settings).isNotEmpty(),
        lastUpload = status.lastUpload?.let { LastUpload(status.lastUploadAtMs ?: 0, it) },
        sinks = status.lastUploadBySink.toList().sortedBy { it.first },
        lastError = status.lastError?.let { LastError(it, status.lastErrorAtMs) },
    )
}

class StatusViewModel(
    status: Flow<SyncStatus>,
    private val settings: Flow<AppSettings>,
    private val devices: PairedDeviceRepository,
    private val work: WorkControl,
    private val diagnostics: DiagnosticsExport,
) : ViewModel() {
    // CDM has no change callback, so the screen refreshes this on resume and after pairing.
    private val device = MutableStateFlow(devices.current())
    private val export = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<StatusUiState> =
        combine(status, settings, device, export) { s, cfg, d, e -> statusUiState(s, cfg, d).copy(export = e) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState())

    fun refreshDevice() {
        device.value = devices.current()
    }

    fun forget() {
        device.value?.let(devices::forget)
        refreshDevice()
    }

    fun syncNow() = work.syncNow()

    /**
     * Replaces any queued upload: one waiting out a back-off would otherwise keep the user
     * waiting; restarting is safe because uploads are idempotent.
     */
    fun uploadNow() {
        viewModelScope.launch { work.uploadNow(settings.first().uploadOverCellular, replace = true) }
    }

    suspend fun exportText(): String = diagnostics.text()

    fun saveExport(target: Uri) {
        viewModelScope.launch {
            export.value = try {
                diagnostics.save(target, diagnostics.text())
                ExportResult.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                ExportResult.FAILED
            } catch (_: SecurityException) {
                ExportResult.FAILED
            }
        }
    }

    fun exportShown() {
        export.value = null
    }

    private companion object {
        // Keeps collecting across a configuration change instead of restarting the flows.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
