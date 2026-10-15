package app.btcourier.work

import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.SyncStopKind
import app.btcourier.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

/**
 * One sync of the paired WiCAN into the store, independent of WorkManager: resolve the address,
 * open the link, run [SyncRunner] over `ble_http`, close the link, record the outcome in [status].
 *
 * At most one sync runs per process: periodic and one-time syncs are separate unique works (a
 * periodic and a one-time request can't share a unique name), so [lock] is what keeps them from
 * opening two links to one device. A sync that finds it held returns [SyncOutcome.AlreadyRunning].
 *
 * @param onConnected runs once the link is up, before any transfer; the worker promotes itself to
 *   a foreground service there, so the service never runs while the device is merely being looked for.
 */
class SyncJob(
    private val pairedAddress: PairedAddress,
    private val links: LinkFactory,
    private val store: Store,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lock: Mutex = processLock,
    private val pageLimit: Int = WicanLoggerSource.DEFAULT_LIMIT,
    private val log: AppLog = AppLog(),
) {
    suspend fun run(sealPolicy: SealPolicy, onConnected: suspend () -> Unit = {}): SyncOutcome {
        if (!lock.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            val address = pairedAddress.current()
            if (address == null) {
                record(SyncResultKind.NOT_PAIRED, "no device paired")
                return SyncOutcome.NotPaired
            }
            val link = try {
                links.open(address)
            } catch (e: IOException) {
                record(SyncResultKind.UNREACHABLE, "device unreachable: ${e.message}")
                return SyncOutcome.Unreachable
            }
            val pendingBefore = store.counters().pendingBatches
            val report = try {
                onConnected()
                transfer(link, sealPolicy)
            } finally {
                link.close()
            }
            return finish(report, pendingBefore)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun transfer(link: GattLink, sealPolicy: SealPolicy): SyncReport = coroutineScope {
        // The transport's notification reader must outlive single requests but not the sync.
        val linkJob = Job(coroutineContext[Job])
        try {
            val transport = BleHttpTransport(link, CoroutineScope(coroutineContext + linkJob))
            val source = WicanLoggerSource(transport, pageLimit, clock)
            SyncRunner(SOURCE_ID, source, store, sealPolicy = sealPolicy, clock = clock).run()
        } finally {
            linkJob.cancel()
        }
    }

    private suspend fun finish(report: SyncReport, pendingBefore: Int): SyncOutcome {
        val counters = store.counters()
        val cursor = store.cursor(SOURCE_ID)
        val (kind, error) = when (val reason = report.stoppedBecause) {
            StopReason.CaughtUp -> SyncResultKind.CAUGHT_UP to null
            is StopReason.NoProgress -> SyncResultKind.DEVICE_ERROR to "device did not advance past ${reason.cursor}"
            is StopReason.DeviceError -> SyncResultKind.DEVICE_ERROR to reason.cause.message
            is StopReason.LinkLost -> SyncResultKind.INTERRUPTED to "link lost: ${reason.cause.message}"
            is StopReason.Truncated -> SyncResultKind.INTERRUPTED to "transfer truncated: ${reason.cause.message}"
            StopReason.Busy -> SyncResultKind.INTERRUPTED to "device busy"
        }
        val stop = stopKind(report.stoppedBecause)
        val now = clock()
        status.update {
            it.copy(
                lastSyncAtMs = now,
                lastSync = kind,
                lastSyncLinesStored = report.linesStored,
                lastSyncPages = report.pagesPulled,
                lastSyncStop = stop,
                cursor = cursor,
            )
                .withCounters(counters)
                .withError(error, now)
        }
        log.info(
            TAG,
            "sync $kind ($stop): ${report.pagesPulled} pages, ${report.linesStored} lines, " +
                "${report.duplicates} duplicates, cursor $cursor, ${counters.pendingBatches} batches pending" +
                (error?.let { ": $it" } ?: ""),
        )
        // Batches are sealed whatever the outcome, so an interrupted sync can still have new ones.
        val newBatches = counters.pendingBatches > pendingBefore
        return if (kind == SyncResultKind.INTERRUPTED) {
            SyncOutcome.Interrupted(report, newBatches)
        } else {
            SyncOutcome.Done(report, newBatches)
        }
    }

    private suspend fun record(kind: SyncResultKind, error: String) {
        val now = clock()
        status.update {
            it.copy(lastSyncAtMs = now, lastSync = kind, lastSyncLinesStored = 0, lastSyncPages = 0, lastSyncStop = null)
                .withError(error, now)
        }
        log.warn(TAG, "sync $kind: $error")
    }

    private fun stopKind(reason: StopReason): SyncStopKind = when (reason) {
        StopReason.CaughtUp -> SyncStopKind.CAUGHT_UP
        is StopReason.NoProgress -> SyncStopKind.NO_PROGRESS
        is StopReason.DeviceError -> SyncStopKind.DEVICE_ERROR
        is StopReason.LinkLost -> SyncStopKind.LINK_LOST
        is StopReason.Truncated -> SyncStopKind.TRUNCATED
        StopReason.Busy -> SyncStopKind.BUSY
    }

    companion object {
        /** Store source id of the WiCAN logger; one paired device for now. */
        const val SOURCE_ID = "wican"

        private const val TAG = "sync"

        private val processLock = Mutex()
    }
}

sealed interface SyncOutcome {
    /** Caught up, or stopped for a reason a retry won't fix (device error). */
    data class Done(val report: SyncReport, val newBatches: Boolean) : SyncOutcome

    /** Link lost, truncated or busy; worth a retry with back-off, resuming at the cursor. */
    data class Interrupted(val report: SyncReport, val newBatches: Boolean) : SyncOutcome

    /** The link could not be opened; the next trigger (presence, periodic) tries again. */
    data object Unreachable : SyncOutcome

    data object NotPaired : SyncOutcome

    data object AlreadyRunning : SyncOutcome
}
