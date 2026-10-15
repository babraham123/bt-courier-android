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
 * Each paired device has its own store source id ([sourceIdFor]), so a replacement dongle starts
 * from its own oldest file instead of the previous device's cursor.
 *
 * Without a link (nothing paired, device unreachable) the job still seals the store, so rows from
 * a short trip reach the sinks once the seal policy's age rule closes them, even if the device
 * stays asleep until the next drive.
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
                return SyncOutcome.NotPaired(sealWithoutLink(sealPolicy, SyncResultKind.NOT_PAIRED, "no device paired"))
            }
            val link = try {
                links.open(address)
            } catch (e: IOException) {
                val sealed = sealWithoutLink(sealPolicy, SyncResultKind.UNREACHABLE, "device unreachable: ${e.message}")
                return SyncOutcome.Unreachable(sealed)
            }
            val sourceId = sourceIdFor(address)
            val report = try {
                onConnected()
                transfer(link, sourceId, sealPolicy)
            } finally {
                link.close()
            }
            return finish(report, sourceId)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun transfer(link: GattLink, sourceId: String, sealPolicy: SealPolicy): SyncReport = coroutineScope {
        // The transport's notification reader must outlive single requests but not the sync.
        val linkJob = Job(coroutineContext[Job])
        try {
            val transport = BleHttpTransport(link, CoroutineScope(coroutineContext + linkJob))
            val source = WicanLoggerSource(transport, pageLimit, clock)
            SyncRunner(sourceId, source, store, sealPolicy = sealPolicy, clock = clock).run()
        } finally {
            linkJob.cancel()
        }
    }

    private suspend fun finish(report: SyncReport, sourceId: String): SyncOutcome {
        val counters = store.counters()
        val cursor = store.cursor(sourceId)
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
        return if (kind == SyncResultKind.INTERRUPTED) SyncOutcome.Interrupted(report) else SyncOutcome.Done(report)
    }

    /** Seals what the policy closes (by age, mostly) and records the sync that never connected. */
    private suspend fun sealWithoutLink(sealPolicy: SealPolicy, kind: SyncResultKind, error: String): Boolean {
        val sealed = store.seal(sealPolicy, clock())
        val counters = store.counters()
        val now = clock()
        status.update {
            it.copy(lastSyncAtMs = now, lastSync = kind, lastSyncLinesStored = 0, lastSyncPages = 0, lastSyncStop = null)
                .withCounters(counters)
                .withError(error, now)
        }
        log.warn(TAG, "sync $kind: $error" + if (sealed.isEmpty()) "" else "; sealed ${sealed.size} aged batches")
        return sealed.isNotEmpty()
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
        /**
         * Store source id of the WiCAN at [address]. Cursors are per device (`<epoch>:<offset>`
         * of its own files), so each address gets its own; case-folded because Android reports
         * addresses upper-case but a hand-entered one may not be.
         */
        fun sourceIdFor(address: String): String = "wican:" + address.uppercase()

        private const val TAG = "sync"

        private val processLock = Mutex()
    }
}

sealed interface SyncOutcome {
    /** The run sealed batches (whatever the outcome), so an upload is worth enqueueing. */
    val newBatches: Boolean

    /** Caught up, or stopped for a reason a retry won't fix (device error). */
    data class Done(val report: SyncReport) : SyncOutcome {
        override val newBatches get() = report.sealed.isNotEmpty()
    }

    /** Link lost, truncated or busy; worth a retry with back-off, resuming at the cursor. */
    data class Interrupted(val report: SyncReport) : SyncOutcome {
        override val newBatches get() = report.sealed.isNotEmpty()
    }

    /** The link could not be opened; the next trigger (presence, periodic) tries again. */
    data class Unreachable(override val newBatches: Boolean) : SyncOutcome

    data class NotPaired(override val newBatches: Boolean) : SyncOutcome

    data object AlreadyRunning : SyncOutcome {
        override val newBatches get() = false
    }
}
