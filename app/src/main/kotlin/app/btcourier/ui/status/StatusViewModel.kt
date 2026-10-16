package app.btcourier.ui.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceSync
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatus
import app.btcourier.data.SyncStopKind
import app.btcourier.data.UploadResultKind
import app.btcourier.data.configuredSinkIds
import app.btcourier.work.WorkControl
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
    /** When the device can next have something to sync; only for [SyncResultKind.NOT_DUE]. */
    val dueAtMs: Long? = null,
)

data class LastUpload(
    val atMs: Long,
    val result: UploadResultKind,
)

data class LastError(
    val message: String,
    val atMs: Long?,
)

/** A paired device with its latest sync; [lastSync] and [cursor] are null before its first sync. */
data class DeviceRow(
    val device: PairedDevice,
    val lastSync: LastSync?,
    val cursor: String?,
)

/** A sink's last outcome under its current name; its id when the sink has been deleted since. */
data class SinkRow(
    val name: String,
    val upload: SinkUpload,
)

data class StatusUiState(
    val loading: Boolean = true,
    /** Every paired device, in pairing order. */
    val devices: List<DeviceRow> = emptyList(),
    /** The drive session running now, or the last one; null before the first. */
    val driveSession: DriveSessionStatus? = null,
    val pendingBatches: Int = 0,
    val bytesStored: Long = 0,
    val cacheCapBytes: Long = AppSettings().cacheCapBytes,
    val droppedLines: Long = 0,
    val droppedBytes: Long = 0,
    val sinkConfigured: Boolean = false,
    val lastUpload: LastUpload? = null,
    /** Every sink of the last upload round, sorted by name. */
    val sinks: List<SinkRow> = emptyList(),
    val lastError: LastError? = null,
    val export: ExportResult? = null,
)

enum class ExportResult { SAVED, FAILED }

/** Builds and writes the diagnostics export; the Android side lives in `AndroidDiagnostics`. */
interface DiagnosticsExport {
    suspend fun text(): String

    suspend fun save(
        target: Uri,
        text: String,
    )
}

private val REACHED_DEVICE = setOf(SyncResultKind.CAUGHT_UP, SyncResultKind.INTERRUPTED, SyncResultKind.DEVICE_ERROR)

private fun DeviceSync.toLastSync(): LastSync {
    val reachedDevice = result in REACHED_DEVICE
    return LastSync(
        atMs = atMs,
        result = result,
        pages = pages.takeIf { reachedDevice },
        lines = linesStored.takeIf { reachedDevice },
        stoppedBecause = stop.takeIf { reachedDevice },
        dueAtMs = dueAtMs.takeIf { result == SyncResultKind.NOT_DUE },
    )
}

/** Pure mapping from the persisted sources to what the status screen shows. */
fun statusUiState(
    status: SyncStatus,
    settings: AppSettings,
    devices: List<PairedDevice>,
): StatusUiState =
    StatusUiState(
        loading = false,
        devices =
            devices.map { device ->
                val sync = status.syncOf(device.address)
                DeviceRow(device, sync?.toLastSync(), sync?.cursor)
            },
        driveSession = status.driveSession,
        pendingBatches = status.pendingBatches,
        bytesStored = status.bytesStored,
        cacheCapBytes = settings.cacheCapBytes,
        droppedLines = status.droppedLines,
        droppedBytes = status.droppedBytes,
        sinkConfigured = configuredSinkIds(settings).isNotEmpty(),
        lastUpload = status.lastUpload?.let { LastUpload(status.lastUploadAtMs ?: 0, it) },
        sinks =
            status.lastUploadBySink
                .map { (id, upload) ->
                    SinkRow(settings.sinks.firstOrNull { it.id == id }?.name ?: id, upload)
                }.sortedBy { it.name },
        lastError = status.lastError?.let { LastError(it, status.lastErrorAtMs) },
    )

class StatusViewModel(
    status: Flow<SyncStatus>,
    private val settings: Flow<AppSettings>,
    private val devices: PairedDeviceRepository,
    private val work: WorkControl,
    private val diagnostics: DiagnosticsExport,
) : ViewModel() {
    // CDM has no change callback, so the screen refreshes this on resume and after pairing. Null
    // until the first read, so the screen never flashes "not paired" while it loads.
    private val paired = MutableStateFlow<List<PairedDevice>?>(null)
    private val export = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<StatusUiState> =
        combine(status, settings, paired, export) { s, cfg, d, e ->
            if (d == null) StatusUiState() else statusUiState(s, cfg, d).copy(export = e)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState())

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        viewModelScope.launch { paired.value = devices.all() }
    }

    fun forget(device: PairedDevice) {
        viewModelScope.launch {
            devices.forget(device)
            paired.value = devices.all()
        }
    }

    fun syncNow(device: PairedDevice) = work.syncNow(device.address, manual = true)

    fun syncAll() = paired.value.orEmpty().forEach { work.syncNow(it.address, manual = true) }

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
            export.value =
                try {
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
