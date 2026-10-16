package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.core.source.Source
import app.btcourier.core.source.SourceException
import app.btcourier.core.store.Store
import app.btcourier.core.store.StoreCounters
import app.btcourier.core.sync.StopReason
import app.btcourier.core.sync.StreamRecorder
import app.btcourier.core.sync.SyncRunner
import app.btcourier.core.transport.GattLink
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceKind
import app.btcourier.data.DriveSessionEnd
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.SettingsRepository
import app.btcourier.data.SyncStatusRepository
import app.btcourier.device.DeviceSupport
import app.btcourier.device.LiveMode
import app.btcourier.device.SourceOptions
import app.btcourier.device.support
import app.btcourier.log.AppLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What asks a running drive session to end before its own rules do. */
enum class DriveStop(
    val end: DriveSessionEnd,
) {
    /**
     * The car head unit disconnected, past the car trigger's debounce. A pulled device gets one
     * last round first: the WiCAN stays awake about 3 minutes after the engine stops, and the
     * trip's last rows are in it.
     */
    CAR_DISCONNECTED(DriveSessionEnd.CAR_DISCONNECTED),

    /** The user pressed Stop: end now. */
    USER(DriveSessionEnd.USER_STOPPED),
}

/**
 * How one drive session ended.
 *
 * @property rounds pull rounds run, or stream emissions received.
 * @property rowsStored data rows newly stored over the whole session.
 */
data class DriveSessionOutcome(
    val address: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val rounds: Int,
    val rowsStored: Long,
    val end: DriveSessionEnd,
)

/**
 * The drive sessions running in this process, by device address (case-insensitive): how a manual
 * sync ([SyncJob]) and the car trigger ([SyncFanOut]) reach a running session.
 */
class DriveSessions {
    private val running = ConcurrentHashMap<String, DriveSession>()

    /** Upper-case addresses of the devices a session runs for. */
    fun running(): Set<String> = running.keys.toSet()

    /** Asks the session of [address] for a round now; false when none runs. */
    fun pullNow(address: String): Boolean = running[address.uppercase()]?.pullNow() ?: false

    /** Asks the session of [address] to end; false when none runs. */
    fun stop(
        address: String,
        reason: DriveStop,
    ): Boolean = running[address.uppercase()]?.stop(reason) ?: false

    /** False when a session already runs for [address]. */
    internal fun register(
        address: String,
        session: DriveSession,
    ): Boolean = running.putIfAbsent(address.uppercase(), session) == null

    internal fun unregister(
        address: String,
        session: DriveSession,
    ) {
        running.remove(address.uppercase(), session)
    }

    companion object {
        /** The instance the app process shares: sessions, syncs and triggers. */
        val process = DriveSessions()
    }
}

/**
 * The rules of a drive session (ticket 0001): while the car is in use, hold one link to a device
 * and keep its rows flowing into the store, independent of the foreground service that hosts it
 * (`DriveSessionService` is a thin adapter).
 *
 * - **Start**: only for a paired device whose kind has a [LiveMode] other than [LiveMode.NONE].
 *   The session takes the device's lock ([DeviceLocks]) for its whole length, waiting up to
 *   [LOCK_WAIT] for a sync that holds it, so every sync of the device meanwhile is a no-op
 *   ([SyncOutcome.AlreadyRunning]) and a manual one asks for a round instead ([pullNow]).
 * - **Loop**, by the kind's live mode: [LiveMode.PULL] runs [SyncRunner] from the stored cursor on
 *   the open link, once at once and then every [AppSettings.drivePullIntervalMinutes] or on
 *   [pullNow]; [LiveMode.STREAM] collects [Source.subscribe] and keeps each emission through one
 *   [StreamRecorder] for the session. After every round or emission that stored rows, and once at
 *   the end, the store is sealed with the seal policy in force and [uploadAfterSeal] applies, the
 *   same rule a sync run's seal follows.
 * - **Link loss** (a pull round that lost the link or had a response cut off, a stream that ended
 *   or failed): the link is reopened, up to [MAX_REOPENS] attempts [REOPEN_DELAY] apart; a link
 *   that comes back is pulled at once and gets the full number of attempts again for its next loss.
 * - **End**, whichever comes first: [stop] ([DriveStop]; a pulled device gets one last round for
 *   [DriveStop.CAR_DISCONNECTED]), no row stored for [AppSettings.driveIdleMinutes], the session
 *   reaching [AppSettings.driveMaxSessionMinutes], or the link lost for good.
 *
 * The session records itself in [status] ([DriveSessionStatus]) at its start, after every round
 * and at its end. One instance runs one session at a time.
 *
 * @param supportOf the kind's [DeviceSupport]; tests swap in kinds the app does not have yet.
 */
class DriveSession(
    private val devices: PairedDevices,
    private val links: LinkFactory,
    private val store: Store,
    private val status: SyncStatusRepository,
    private val work: WorkControl,
    private val locks: DeviceLocks,
    private val clock: () -> Long,
    private val settings: SettingsRepository,
    private val sessions: DriveSessions = DriveSessions.process,
    private val supportOf: (DeviceKind) -> DeviceSupport = { it.support },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val pageLimit: Int = WicanLoggerSource.DEFAULT_LIMIT,
    private val retry: SyncRetryPolicy = SyncRetryPolicy(),
    private val log: AppLog = AppLog(),
) {
    private sealed interface Control {
        data object Wake : Control

        data class Stop(
            val reason: DriveStop,
        ) : Control
    }

    @Volatile
    private var control: Channel<Control>? = null

    /** Asks the running session for a round now; false when it is not running. */
    fun pullNow(): Boolean = control?.trySend(Control.Wake)?.isSuccess == true

    /** Asks the running session to end; false when it is not running. */
    fun stop(reason: DriveStop): Boolean = control?.trySend(Control.Stop(reason))?.isSuccess == true

    /**
     * Runs a session with the device at [address] until one of the end rules applies.
     *
     * @param onConnected runs once, when the first link is up.
     */
    suspend fun run(
        address: String,
        onConnected: suspend () -> Unit = {},
    ): DriveSessionOutcome {
        val requestedAtMs = clock()
        val device = devices.all().firstOrNull { it.address.equals(address, ignoreCase = true) }
        val support = device?.let { supportOf(it.kind) }
        if (device == null || support == null || support.liveMode == LiveMode.NONE) {
            return notStarted(address, requestedAtMs, DriveSessionEnd.NOT_LIVE)
        }
        val channel = Channel<Control>(Channel.UNLIMITED)
        synchronized(this) {
            if (control != null) return notStarted(address, requestedAtMs, DriveSessionEnd.BUSY)
            control = channel
        }
        try {
            if (!sessions.register(
                    device.address,
                    this,
                )
            ) {
                return notStarted(address, requestedAtMs, DriveSessionEnd.BUSY)
            }
            try {
                val lock = locks.of(device.address)
                withTimeoutOrNull(LOCK_WAIT) { lock.lock() }
                    ?: return notStarted(address, requestedAtMs, DriveSessionEnd.BUSY)
                try {
                    // Starts once the lock is held: a sync it waited for is not session time.
                    return Run(device, support, clock(), channel, timing(settings.current())).run(onConnected)
                } finally {
                    lock.unlock()
                }
            } finally {
                sessions.unregister(device.address, this)
            }
        } finally {
            control = null
            channel.close()
        }
    }

    private fun notStarted(
        address: String,
        atMs: Long,
        end: DriveSessionEnd,
    ): DriveSessionOutcome {
        log.warn(TAG, "drive session $address not started: $end")
        return DriveSessionOutcome(address.uppercase(), atMs, atMs, 0, 0, end)
    }

    private data class Timing(
        val pullIntervalMs: Long,
        val idleMs: Long,
        val maxSessionMs: Long,
    )

    private fun timing(s: AppSettings) =
        Timing(
            pullIntervalMs = s.drivePullIntervalMinutes.minutes.inWholeMilliseconds,
            idleMs = s.driveIdleMinutes.minutes.inWholeMilliseconds,
            maxSessionMs = s.driveMaxSessionMinutes.minutes.inWholeMilliseconds,
        )

    /** How the session's time on one link ended. */
    private sealed interface Segment {
        data class Ended(
            val end: DriveSessionEnd,
        ) : Segment

        data class LinkDown(
            val cause: Exception?,
        ) : Segment
    }

    /** What a stream's collector hands the session loop. */
    private sealed interface Emission {
        data class Rows(
            val rows: List<String>,
        ) : Emission

        data class Ended(
            val cause: Exception?,
        ) : Emission
    }

    /** One session's state, from the lock taken to the outcome. */
    private inner class Run(
        private val device: PairedDevice,
        private val support: DeviceSupport,
        private val startedAtMs: Long,
        private val control: ReceiveChannel<Control>,
        private val timing: Timing,
    ) {
        private val address = device.address.uppercase()
        private val sourceId = SyncJob.sourceIdFor(support, device.address)
        private val recorder by lazy { StreamRecorder(sourceId, store, clock = { startedAtMs }) }
        private var rounds = 0
        private var rows = 0L
        private var lastStoredAtMs = startedAtMs
        private var lastFailure: String? = null

        /** Why the session ended while it had no link; set by [reopen] when that ends it. */
        private var ended: DriveSessionEnd? = null

        suspend fun run(onConnected: suspend () -> Unit): DriveSessionOutcome {
            status.update { it.copy(driveSession = snapshot()) }
            log.info(TAG, "drive session ${device.kind} $address started (${support.liveMode})")
            val end = connected(onConnected)
            val counters = seal()
            val endedAtMs = clock()
            val error = lastFailure?.takeIf { end == DriveSessionEnd.LINK_LOST || end == DriveSessionEnd.UNREACHABLE }
            status.update {
                it
                    .copy(driveSession = snapshot(endedAtMs, end))
                    .withCounters(counters)
                    .withError(error?.let { e -> "drive session: $e" }, endedAtMs)
            }
            log.info(
                TAG,
                "drive session ${device.kind} $address ended $end after $rounds rounds, $rows rows, " +
                    "${counters.pendingBatches} batches pending" + (error?.let { ": $it" } ?: ""),
            )
            return DriveSessionOutcome(address, startedAtMs, endedAtMs, rounds, rows, end)
        }

        private fun snapshot(
            endedAtMs: Long? = null,
            end: DriveSessionEnd? = null,
        ) = DriveSessionStatus(address, startedAtMs, rounds, rows, endedAtMs, end)

        /** Opens the first link, then runs the live loop on it and on every reopened one. */
        private suspend fun connected(onConnected: suspend () -> Unit): DriveSessionEnd {
            var link =
                try {
                    links.open(device.address, device.kind)
                } catch (e: IOException) {
                    lastFailure = "device unreachable: ${e.message}"
                    return DriveSessionEnd.UNREACHABLE
                }
            onConnected()
            while (true) {
                val segment =
                    try {
                        support.withSource(link, SourceOptions(clock, zone(), pageLimit)) { live(it) }
                    } catch (e: IOException) {
                        Segment.LinkDown(e)
                    } finally {
                        link.close()
                    }
                when (segment) {
                    is Segment.Ended -> {
                        return segment.end
                    }

                    is Segment.LinkDown -> {
                        lastFailure = "link lost: ${segment.cause?.message ?: "stream ended"}"
                        log.warn(TAG, "drive session $address $lastFailure; reopening")
                        link = reopen() ?: return ended ?: DriveSessionEnd.LINK_LOST
                    }
                }
            }
        }

        /** A new link, or null once [MAX_REOPENS] attempts failed or the session ended meanwhile. */
        private suspend fun reopen(): GattLink? {
            repeat(MAX_REOPENS) {
                val c = nextControl(REOPEN_DELAY.inWholeMilliseconds)
                if (c is Control.Stop) {
                    ended = c.reason.end
                    return null
                }
                deadlineEnd()?.let {
                    ended = it
                    return null
                }
                try {
                    return links.open(device.address, device.kind)
                } catch (e: IOException) {
                    lastFailure = "link lost, not reopened: ${e.message}"
                    log.warn(TAG, "drive session $address reopen failed: ${e.message}")
                }
            }
            return null
        }

        // The one place a live mode becomes a loop; a new mode is one branch here.
        private suspend fun live(source: Source): Segment =
            when (support.liveMode) {
                LiveMode.PULL -> pull(source)
                LiveMode.STREAM -> stream(source)
                LiveMode.NONE -> Segment.Ended(DriveSessionEnd.NOT_LIVE)
            }

        private suspend fun pull(source: Source): Segment {
            var lastRound = false
            while (true) {
                val report = SyncRunner(sourceId, source, store, busyBackoff = retry.busyBackoff).run()
                roundDone(report.linesStored)
                if (lastRound) return Segment.Ended(DriveSessionEnd.CAR_DISCONNECTED)
                // A cut-off response is how a link dropping mid-transfer usually shows; reopen either way.
                when (val stop = report.stoppedBecause) {
                    is StopReason.LinkLost -> return Segment.LinkDown(stop.cause)
                    is StopReason.Truncated -> return Segment.LinkDown(stop.cause)
                    else -> Unit
                }
                val c = nextControl(timing.pullIntervalMs)
                when {
                    c == Control.Stop(DriveStop.USER) -> return Segment.Ended(DriveSessionEnd.USER_STOPPED)
                    c is Control.Stop -> lastRound = true
                    else -> deadlineEnd()?.let { return Segment.Ended(it) }
                }
            }
        }

        private suspend fun stream(source: Source): Segment =
            coroutineScope {
                val emissions = Channel<Emission>(Channel.UNLIMITED)
                val collector =
                    launch {
                        val cause =
                            try {
                                source.subscribe().collect { emissions.send(Emission.Rows(it)) }
                                null
                            } catch (e: IOException) {
                                e
                            } catch (e: SourceException) {
                                e
                            }
                        emissions.send(Emission.Ended(cause))
                    }
                try {
                    collectStream(emissions)
                } finally {
                    collector.cancel()
                }
            }

        private suspend fun collectStream(emissions: ReceiveChannel<Emission>): Segment {
            while (true) {
                val wait = deadlineAtMs() - clock()
                val event =
                    if (wait <= 0) {
                        null
                    } else {
                        withTimeoutOrNull(wait) {
                            select<Any> {
                                control.onReceive { it }
                                emissions.onReceive { it }
                            }
                        }
                    }
                when (event) {
                    is Emission.Rows -> roundDone(recorder.record(event.rows).toLong())

                    is Emission.Ended -> return Segment.LinkDown(event.cause)

                    // A stream keeps nothing on the device, so there is no last round to take.
                    is Control.Stop -> return Segment.Ended(event.reason.end)

                    else -> deadlineEnd()?.let { return Segment.Ended(it) }
                }
            }
        }

        private suspend fun roundDone(stored: Long) {
            rounds++
            rows += stored
            val counters =
                if (stored > 0) {
                    lastStoredAtMs = clock()
                    seal()
                } else {
                    null
                }
            status.update {
                val updated = it.copy(driveSession = snapshot())
                if (counters == null) updated else updated.withCounters(counters)
            }
        }

        private suspend fun seal(): StoreCounters {
            val current = settings.current()
            val now = clock()
            val seal = store.sealNow(current.sealPolicy, now)
            work.uploadAfterSeal(seal.sealed.isNotEmpty(), seal.dueAtMs, current.uploadOverCellular, now)
            return seal.counters
        }

        private fun deadlineAtMs() = minOf(lastStoredAtMs + timing.idleMs, startedAtMs + timing.maxSessionMs)

        private fun deadlineEnd(): DriveSessionEnd? {
            val now = clock()
            return when {
                now >= startedAtMs + timing.maxSessionMs -> DriveSessionEnd.SESSION_CAP
                now >= lastStoredAtMs + timing.idleMs -> DriveSessionEnd.IDLE
                else -> null
            }
        }

        /**
         * Waits up to [ms], and no later than the session's deadline, for [pullNow] or [stop];
         * null when the time ran out.
         */
        private suspend fun nextControl(ms: Long): Control? {
            val wait = minOf(ms, deadlineAtMs() - clock())
            return if (wait <= 0) control.tryReceive().getOrNull() else withTimeoutOrNull(wait) { control.receive() }
        }
    }

    companion object {
        /** How long a start waits for a sync holding the device's link to finish. */
        val LOCK_WAIT: Duration = 2.minutes

        /** Reopen attempts after the link drops, each [REOPEN_DELAY] after the previous one. */
        const val MAX_REOPENS = 2

        /** Gives the stack and the device time to drop the old connection before reconnecting. */
        val REOPEN_DELAY: Duration = 10.seconds

        private const val TAG = "drive"
    }
}
