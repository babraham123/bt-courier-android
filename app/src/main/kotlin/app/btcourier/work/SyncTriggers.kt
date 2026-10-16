package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.device.LiveMode
import app.btcourier.device.support

/** What asks for background syncs; each syncs a different set of paired devices ([planTrigger]). */
sealed interface SyncTrigger {
    /** The 15-minute periodic work. */
    data object Periodic : SyncTrigger

    /** CDM reports the device at [address] in range. */
    data class Presence(
        val address: String,
    ) : SyncTrigger

    /** The car head unit [connected] or disconnected; concerns the kinds that live in the car. */
    data class Car(
        val connected: Boolean,
    ) : SyncTrigger
}

/**
 * What one trigger does, by paired address: enqueue a one-time sync, start a drive
 * session, or tell a running session the car disconnected. A device is in at most one list.
 */
data class TriggerPlan(
    val syncs: List<String> = emptyList(),
    val sessionStarts: List<String> = emptyList(),
    val sessionStops: List<String> = emptyList(),
)

/**
 * The trigger rules, in pairing order:
 *
 * - Which devices a trigger concerns: periodic all, presence the one in range, the car the kinds
 *   that live in it ([app.btcourier.device.DeviceSupport.syncsOnCarTrigger]).
 * - Presence or the car connecting, with [driveSession] on, starts a session for each concerned
 *   device whose kind has a live mode; the others get a sync as before.
 * - A device a session already runs for gets nothing more from presence, the car connecting or
 *   the periodic work, and the car disconnecting stops its session (which takes the last round)
 *   whatever [driveSession] says now. Without a session the disconnect syncs, as before.
 * - A streaming device ([LiveMode.STREAM]) is never synced: it keeps nothing to pull.
 *
 * @param running upper-case addresses of the devices a drive session runs for.
 */
fun planTrigger(
    trigger: SyncTrigger,
    devices: List<PairedDevice>,
    driveSession: Boolean = false,
    running: Set<String> = emptySet(),
    liveModeOf: (PairedDevice) -> LiveMode = { it.kind.support.liveMode },
): TriggerPlan {
    val concerned =
        when (trigger) {
            SyncTrigger.Periodic -> devices
            is SyncTrigger.Presence -> devices.filter { it.address.equals(trigger.address, ignoreCase = true) }
            is SyncTrigger.Car -> devices.filter { it.kind.support.syncsOnCarTrigger }
        }
    val (inSession, idle) = concerned.partition { it.address.uppercase() in running }
    val starts =
        if (driveSession && (trigger is SyncTrigger.Presence || trigger == SyncTrigger.Car(connected = true))) {
            idle.filter { liveModeOf(it) != LiveMode.NONE }
        } else {
            emptyList()
        }
    val syncs = (idle - starts.toSet()).filter { liveModeOf(it) != LiveMode.STREAM }
    val stops = if (trigger == SyncTrigger.Car(connected = false)) inSession else emptyList()
    return TriggerPlan(syncs.map { it.address }, starts.map { it.address }, stops.map { it.address })
}

/** The drive-session side of the triggers, as [SyncFanOut] uses it. */
interface DriveSessionStarter {
    /** The "keep syncing while driving" setting. */
    suspend fun enabled(): Boolean

    /** Upper-case addresses of the devices a drive session runs for. */
    fun running(): Set<String>

    /** Asks for a session with the device at [address]; false when the system refused to start one. */
    fun start(address: String): Boolean

    /** Tells the session with the device at [address] that the car disconnected. */
    fun carDisconnected(address: String)

    /** No drive sessions: every trigger syncs. */
    object None : DriveSessionStarter {
        override suspend fun enabled() = false

        override fun running() = emptySet<String>()

        override fun start(address: String) = false

        override fun carDisconnected(address: String) = Unit
    }
}

/**
 * Carries out a trigger's [planTrigger]: one sync per device it syncs, [enqueue] being an
 * automatic (not manual) [WorkControl.syncNow] in the app, since no trigger is the user asking;
 * the drive sessions it starts or stops through [sessions]. A session the system refuses to start
 * becomes a sync, so the trigger still lands the device's rows.
 */
class SyncFanOut(
    private val devices: PairedDevices,
    private val sessions: DriveSessionStarter = DriveSessionStarter.None,
    private val enqueue: (address: String) -> Unit,
) {
    /** @return the addresses enqueued. */
    suspend fun fire(trigger: SyncTrigger): List<String> {
        val plan = planTrigger(trigger, devices.all(), sessions.enabled(), sessions.running())
        plan.sessionStops.forEach(sessions::carDisconnected)
        val refused = plan.sessionStarts.filterNot(sessions::start)
        return (plan.syncs + refused).onEach(enqueue)
    }

    /** Whether any device is paired, synced by triggers or not. */
    suspend fun anyPaired(): Boolean = devices.all().isNotEmpty()
}
