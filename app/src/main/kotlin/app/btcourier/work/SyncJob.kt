package app.btcourier.work

import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.transport.GattLink
import app.btcourier.core.transport.blehttp.BleHttpTransport
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.DeviceKind
import app.btcourier.data.DeviceSync
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.SyncStopKind
import app.btcourier.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * One sync of one paired device into the store, independent of WorkManager: look the device up,
 * open a link with its kind's GATT profile, run [SyncRunner] over its kind's source (the WiCAN's
 * logger over `ble_http`, the Colmi ring's day logs), close the link, record the outcome in
 * [status] under its address. Each paired device has its own store source id ([sourceIdFor]), so
 * a replacement device starts from its own oldest data instead of the previous device's cursor.
 *
 * Without a link (device not paired, unreachable) the job still seals the store, so rows from a
 * short trip reach the sinks once the seal policy's age rule closes them, even if the device
 * stays asleep until the next drive.
 *
 * At most one sync runs per device: periodic fan-out, presence and manual syncs can enqueue
 * overlapping works for one device, so [locks] is what keeps them from opening two links to it. A
 * sync that finds its device's lock held returns [SyncOutcome.AlreadyRunning]. Different devices
 * sync in parallel.
 *
 * @param zone the phone's zone, which the ring's clock is set to and its days are counted in.
 */
class SyncJob(
    private val devices: PairedDevices,
    private val links: LinkFactory,
    private val store: Store,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locks: DeviceLocks = processLocks,
    private val pageLimit: Int = WicanLoggerSource.DEFAULT_LIMIT,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: AppLog = AppLog(),
) {
    /**
     * @param address the device to sync; null when nothing is paired, which only seals.
     * @param onConnected runs once the link is up, before any transfer; the worker promotes itself
     *   to a foreground service there, so the service never runs while the device is merely being
     *   looked for.
     */
    suspend fun run(address: String?, sealPolicy: SealPolicy, onConnected: suspend () -> Unit = {}): SyncOutcome {
        val device = address?.let { a -> devices.all().firstOrNull { it.address.equals(a, ignoreCase = true) } }
        if (device == null) {
            val what = if (address == null) "no device paired" else "device not paired"
            return SyncOutcome.NotPaired(sealWithoutLink(sealPolicy, null, SyncResultKind.NOT_PAIRED, what))
        }
        val lock = locks.of(device.address)
        if (!lock.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            val link = try {
                links.open(device.address, device.kind)
            } catch (e: IOException) {
                val sealed = sealWithoutLink(sealPolicy, device.address, SyncResultKind.UNREACHABLE, "device unreachable: ${e.message}")
                return SyncOutcome.Unreachable(sealed)
            }
            val sourceId = sourceIdFor(device.kind, device.address)
            val report = try {
                onConnected()
                transfer(link, device.kind, sourceId, sealPolicy)
            } finally {
                link.close()
            }
            return finish(report, device, sourceId)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun transfer(link: GattLink, kind: DeviceKind, sourceId: String, sealPolicy: SealPolicy): SyncReport = coroutineScope {
        // The WiCAN transport's notification reader must outlive single requests but not the sync.
        val linkJob = Job(coroutineContext[Job])
        try {
            val source = when (kind) {
                DeviceKind.WICAN -> WicanLoggerSource(BleHttpTransport(link, CoroutineScope(coroutineContext + linkJob)), pageLimit, clock)
                DeviceKind.COLMI_RING -> ColmiRingSource(link, zone(), clock)
            }
            SyncRunner(sourceId, source, store, sealPolicy = sealPolicy, clock = clock).run()
        } finally {
            linkJob.cancel()
        }
    }

    private suspend fun finish(report: SyncReport, device: PairedDevice, sourceId: String): SyncOutcome {
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
            it.withSync(device.address, DeviceSync(now, kind, report.linesStored, report.pagesPulled, stop, cursor))
                .withCounters(counters)
                .withError(error, now)
        }
        log.info(
            TAG,
            "sync ${device.kind} $kind ($stop): ${report.pagesPulled} pages, ${report.linesStored} lines, " +
                "${report.duplicates} duplicates, cursor $cursor, ${counters.pendingBatches} batches pending" +
                (error?.let { ": $it" } ?: ""),
        )
        return if (kind == SyncResultKind.INTERRUPTED) SyncOutcome.Interrupted(report) else SyncOutcome.Done(report)
    }

    /**
     * Seals what the policy closes (by age, mostly) and records the sync that never connected:
     * under [address] when it is a paired device, keeping its cursor; otherwise only as the error.
     */
    private suspend fun sealWithoutLink(sealPolicy: SealPolicy, address: String?, kind: SyncResultKind, error: String): Boolean {
        val sealed = store.seal(sealPolicy, clock())
        val counters = store.counters()
        val now = clock()
        status.update {
            val recorded = address?.let { a -> it.withSync(a, DeviceSync(now, kind, cursor = it.syncOf(a)?.cursor)) } ?: it
            recorded.withCounters(counters).withError(error, now)
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
         * Store source id of the [kind] device at [address]. Cursors are per device (a WiCAN's
         * `<epoch>:<offset>` of its own files, a ring's last day), so each address gets its own;
         * case-folded because Android reports addresses upper-case but a hand-entered one may not
         * be. The prefix also names the device's uploaded batches.
         */
        fun sourceIdFor(kind: DeviceKind, address: String): String = when (kind) {
            DeviceKind.WICAN -> "wican:"
            DeviceKind.COLMI_RING -> "colmi:"
        } + address.uppercase()

        private const val TAG = "sync"

        private val processLocks = DeviceLocks()
    }
}

/** One [Mutex] per device address (case-insensitive), created on first use. */
class DeviceLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun of(address: String): Mutex = locks.getOrPut(address.uppercase()) { Mutex() }
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
