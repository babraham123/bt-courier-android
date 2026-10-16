package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.core.store.SealPolicy
import app.btcourier.core.store.Store
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.SyncReport
import app.btcourier.core.sync.SyncRunner
import app.btcourier.core.transport.GattLink
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.DeviceKind
import app.btcourier.data.DeviceSync
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatusRepository
import app.btcourier.data.SyncStopKind
import app.btcourier.device.DeviceSupport
import app.btcourier.device.SourceOptions
import app.btcourier.device.support
import app.btcourier.log.AppLog
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One sync of one paired device into the store, independent of WorkManager: look the device up,
 * open a link with its kind's GATT profile, run [SyncRunner] over the source its kind's
 * [support][app.btcourier.device.DeviceSupport] opens on the link, close the link, then finish.
 * Each paired device has its own store source id ([sourceIdFor]), so a replacement device starts
 * from its own oldest data instead of the previous device's cursor.
 *
 * An automatic sync first asks the device's kind when a sync from its stored cursor could next
 * store anything ([DeviceSupport.nextDueAtMs][app.btcourier.device.DeviceSupport.nextDueAtMs]),
 * and before then ends [SyncResultKind.NOT_DUE] without opening a link: the periodic work and
 * presence would otherwise connect to a caught-up ring every 15 minutes for a slot or two. The
 * first automatic sync from the due time on connects, so a ring is pulled within one periodic
 * interval of its due time. A manual sync always connects.
 *
 * Every run that gets past the lock, and every skipped one, ends through one path, whether or not
 * it reached the device:
 * seal what [SealPolicy] closes, record the [SyncOutcome] in [status] (under the device's address
 * when it is paired), log it and return it. Rows the seal leaves open come with the time the
 * policy's age rule closes them ([SyncOutcome.sealDueAtMs]), for which [SyncWork] schedules an
 * upload, so they reach the sinks then even if the device stays asleep until the next drive.
 *
 * At most one sync runs per device: periodic fan-out, presence and manual syncs can enqueue
 * overlapping works for one device, so [locks] is what keeps them from opening two links to it;
 * the pair wizard's probe takes the same lock, and so does a [DriveSession] for its whole length.
 * A sync that finds its device's lock held returns [SyncOutcome.AlreadyRunning], except a manual
 * one while a drive session holds it: the session runs a round at once instead
 * ([DriveSession.pullNow]), so the user's request is answered on the open link and no second link
 * is tried ([SyncOutcome.HandedToSession]). Different devices sync in parallel.
 *
 * @param retry when a sync is tried again, within the run and across runs; see [SyncRetryPolicy].
 * @param zone the phone's zone, which the ring's clock is set to and its days are counted in.
 */
class SyncJob(
    private val devices: PairedDevices,
    private val links: LinkFactory,
    private val store: Store,
    private val status: SyncStatusRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locks: DeviceLocks = DeviceLocks.process,
    private val pageLimit: Int = WicanLoggerSource.DEFAULT_LIMIT,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: AppLog = AppLog(),
    private val retry: SyncRetryPolicy = SyncRetryPolicy(),
    private val sessions: DriveSessions = DriveSessions.process,
) {
    /**
     * @param address the device to sync; null when nothing is paired, which only seals.
     * @param attempt how many runs of this sync came before this one in a row (WorkManager's
     *   `runAttemptCount`); [SyncOutcome.retryWorthwhile] weighs it against [SyncRetryPolicy.maxRuns].
     * @param manual the user asked for this sync, so it connects even when nothing is due.
     * @param onConnected runs once the link is up, before any transfer; the worker promotes itself
     *   to a foreground service there, so the service never runs while the device is merely being
     *   looked for.
     */
    suspend fun run(
        address: String?,
        sealPolicy: SealPolicy,
        attempt: Int = 0,
        manual: Boolean = false,
        onConnected: suspend () -> Unit = {},
    ): SyncOutcome {
        val device =
            address?.let { a -> devices.all().firstOrNull { it.address.equals(a, ignoreCase = true) } }
                ?: return finish(End.NotPaired(address), null, sealPolicy, attempt)
        // Before the lock, so a skip never makes a manual sync of the same device AlreadyRunning.
        if (!manual) {
            val cursor = store.cursor(sourceIdFor(device.kind, device.address))
            val due = device.kind.support.nextDueAtMs(cursor, zone())
            if (due != null && clock() < due) return finish(End.NotDue(due), device, sealPolicy, attempt)
        }
        val lock = locks.of(device.address)
        if (!lock.tryLock()) {
            return if (manual && sessions.pullNow(device.address)) {
                log.info(TAG, "sync ${device.kind} handed to the running drive session")
                SyncOutcome.HandedToSession
            } else {
                SyncOutcome.AlreadyRunning
            }
        }
        try {
            val link =
                try {
                    links.open(device.address, device.kind)
                } catch (e: IOException) {
                    return finish(End.Unreachable(e), device, sealPolicy, attempt)
                }
            val report =
                try {
                    onConnected()
                    transfer(link, device)
                } finally {
                    link.close()
                }
            return finish(End.Reached(report), device, sealPolicy, attempt)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun transfer(
        link: GattLink,
        device: PairedDevice,
    ): SyncReport =
        device.kind.support.withSource(link, SourceOptions(clock, zone(), pageLimit, device.vehicleProfile)) { source ->
            SyncRunner(sourceIdFor(device.kind, device.address), source, store, busyBackoff = retry.busyBackoff).run()
        }

    /** How a run ended, before [finish] turns it into its [SyncOutcome]. */
    private sealed interface End {
        data class Reached(
            val report: SyncReport,
        ) : End

        data class Unreachable(
            val cause: IOException,
        ) : End

        data class NotPaired(
            val address: String?,
        ) : End

        data class NotDue(
            val dueAtMs: Long,
        ) : End
    }

    private suspend fun finish(
        end: End,
        device: PairedDevice?,
        sealPolicy: SealPolicy,
        attempt: Int,
    ): SyncOutcome.Recorded {
        val seal = store.sealNow(sealPolicy, clock())
        val counters = seal.counters
        val cursor = device?.let { store.cursor(sourceIdFor(it.kind, it.address)) }
        val outcome = outcomeOf(end, cursor, seal.sealed, attempt).copy(sealDueAtMs = seal.dueAtMs)
        val now = clock()
        status.update {
            val recorded = device?.let { d -> it.withSync(d.address, outcome.deviceSync(now)) } ?: it
            recorded.withCounters(counters).withError(outcome.error, now)
        }
        val report = (end as? End.Reached)?.report
        val line =
            buildString {
                append("sync ${device?.kind ?: "-"} ${outcome.result}")
                if (report != null) {
                    append(" (${outcome.stop}): ${report.pagesPulled} pages, ${report.linesStored} lines, ")
                    append("${report.duplicates} duplicates, cursor $cursor")
                }
                outcome.dueAtMs?.let { append(" until ${Instant.ofEpochMilli(it)}") }
                append("; sealed ${seal.sealed.size}, ${counters.pendingBatches} batches pending")
                outcome.sealDueAtMs?.let { append(", next seal ${Instant.ofEpochMilli(it)}") }
                outcome.error?.let { append(": $it") }
            }
        if (report != null || end is End.NotDue) log.info(TAG, line) else log.warn(TAG, line)
        return outcome
    }

    /**
     * The one place a run's end becomes what is recorded and whether another run is worth it. A
     * new [StopReason] is one branch here, plus its [SyncStopKind] and the status screen's label.
     */
    private fun outcomeOf(
        end: End,
        cursor: String?,
        sealed: List<String>,
        attempt: Int,
    ): SyncOutcome.Recorded {
        fun reached(
            report: SyncReport,
            result: SyncResultKind,
            stop: SyncStopKind,
            error: String?,
        ) = SyncOutcome.Recorded(
            result,
            stop,
            error,
            report.linesStored,
            report.pagesPulled,
            cursor,
            sealed,
            retryWorthwhile = retry.anotherRun(interrupted = result == SyncResultKind.INTERRUPTED, attempt),
        )
        return when (end) {
            is End.NotPaired -> {
                SyncOutcome.Recorded(
                    SyncResultKind.NOT_PAIRED,
                    error = if (end.address == null) "no device paired" else "device not paired",
                    sealed = sealed,
                )
            }

            is End.Unreachable -> {
                SyncOutcome.Recorded(
                    SyncResultKind.UNREACHABLE,
                    error = "device unreachable: ${end.cause.message}",
                    cursor = cursor,
                    sealed = sealed,
                )
            }

            is End.NotDue -> {
                SyncOutcome.Recorded(SyncResultKind.NOT_DUE, cursor = cursor, sealed = sealed, dueAtMs = end.dueAtMs)
            }

            is End.Reached -> {
                when (val reason = end.report.stoppedBecause) {
                    StopReason.CaughtUp -> {
                        reached(end.report, SyncResultKind.CAUGHT_UP, SyncStopKind.CAUGHT_UP, null)
                    }

                    is StopReason.NoProgress -> {
                        reached(
                            end.report,
                            SyncResultKind.DEVICE_ERROR,
                            SyncStopKind.NO_PROGRESS,
                            "device did not advance past ${reason.cursor}",
                        )
                    }

                    is StopReason.DeviceError -> {
                        reached(
                            end.report,
                            SyncResultKind.DEVICE_ERROR,
                            SyncStopKind.DEVICE_ERROR,
                            reason.cause.message,
                        )
                    }

                    is StopReason.LinkLost -> {
                        reached(
                            end.report,
                            SyncResultKind.INTERRUPTED,
                            SyncStopKind.LINK_LOST,
                            "link lost: ${reason.cause.message}",
                        )
                    }

                    is StopReason.Truncated -> {
                        reached(
                            end.report,
                            SyncResultKind.INTERRUPTED,
                            SyncStopKind.TRUNCATED,
                            "transfer truncated: ${reason.cause.message}",
                        )
                    }

                    StopReason.Busy -> {
                        reached(end.report, SyncResultKind.INTERRUPTED, SyncStopKind.BUSY, "device busy")
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Store source id of the [kind] device at [address]. Cursors are per device (a WiCAN's
         * `<epoch>:<offset>` of its own files, a ring's last day), so each address gets its own;
         * case-folded because Android reports addresses upper-case but a hand-entered one may not
         * be. The prefix also names the device's uploaded batches.
         */
        fun sourceIdFor(
            kind: DeviceKind,
            address: String,
        ): String = sourceIdFor(kind.support, address)

        /** [sourceIdFor] by the kind's [support]. */
        fun sourceIdFor(
            support: DeviceSupport,
            address: String,
        ): String = support.sourcePrefix + address.uppercase()

        private const val TAG = "sync"
    }
}

/**
 * When a sync is tried again, in two steps:
 *
 * 1. Within a run, a device call answered 429 (busy) is retried on the same link after each of
 *    [busyBackoff]. Still busy after the last wait, the run ends interrupted.
 * 2. Across runs, an interrupted run (link lost, transfer truncated, still busy) is worth another
 *    one, resuming at the stored cursor, until [maxRuns] runs in a row have been made; the worker
 *    spaces them with WorkManager's back-off. After that, the next trigger (presence, periodic)
 *    takes over. A run that caught up, met a device error or never reached the device is not
 *    run again: a retry would not change its answer, and the next trigger tries anyway.
 */
data class SyncRetryPolicy(
    val busyBackoff: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds),
    val maxRuns: Int = 5,
) {
    /** Whether a run ending [interrupted] after [attempt] earlier runs in a row earns another. */
    fun anotherRun(
        interrupted: Boolean,
        attempt: Int,
    ): Boolean = interrupted && attempt + 1 < maxRuns
}

/**
 * How one run of [SyncJob] ended: the one vocabulary the status record, the log, the upload
 * trigger and the worker's retry decision read. [SyncJob] computes it in one place, from why the
 * run stopped.
 */
sealed interface SyncOutcome {
    /**
     * Ids of the batches this run's seal created, whatever the outcome. Not the change in pending
     * batches: the same seal may evict old batches at the cache cap, and an upload running
     * meanwhile may drain some.
     */
    val sealed: List<String>

    /** Whether the worker should run this sync again; [SyncRetryPolicy] says when. */
    val retryWorthwhile: Boolean

    /** The run sealed batches, so an upload is worth enqueueing. */
    val newBatches: Boolean get() = sealed.isNotEmpty()

    /**
     * When the age rule of the run's [SealPolicy] closes the lines the run's seal left open; null
     * when it left none, which is always the case with an age of 0.
     */
    val sealDueAtMs: Long?

    /**
     * A run that was sealed and recorded, whether or not it reached the device.
     *
     * @property result the status screen's headline for the run.
     * @property stop why the transfer stopped; null when the run never reached the device.
     * @property error the status screen's last error; null when the run caught up.
     * @property linesStored data rows newly stored; 0 when the run never reached the device.
     * @property pages export responses received; 0 when the run never reached the device.
     * @property cursor the device's store cursor after the run; null when no paired device was
     *   found or nothing was ever stored for it.
     * @property dueAtMs for [SyncResultKind.NOT_DUE], when the device can next have something to
     *   sync; null otherwise.
     */
    data class Recorded(
        val result: SyncResultKind,
        val stop: SyncStopKind? = null,
        val error: String? = null,
        val linesStored: Long = 0,
        val pages: Int = 0,
        val cursor: String? = null,
        override val sealed: List<String> = emptyList(),
        override val retryWorthwhile: Boolean = false,
        val dueAtMs: Long? = null,
        override val sealDueAtMs: Long? = null,
    ) : SyncOutcome {
        /** What [app.btcourier.data.SyncStatus] keeps of this run under the device's address. */
        fun deviceSync(atMs: Long) = DeviceSync(atMs, result, linesStored, pages, stop, cursor, dueAtMs)
    }

    /** Another run holds the device's lock; nothing was sealed or recorded. */
    data object AlreadyRunning : SyncOutcome {
        override val sealed get() = emptyList<String>()
        override val retryWorthwhile get() = false
        override val sealDueAtMs: Long? get() = null
    }

    /**
     * A manual sync found a drive session holding the device's link and asked it for a round now;
     * the session seals, records and uploads that round itself.
     */
    data object HandedToSession : SyncOutcome {
        override val sealed get() = emptyList<String>()
        override val retryWorthwhile get() = false
        override val sealDueAtMs: Long? get() = null
    }
}
