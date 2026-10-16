package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDevices
import app.btcourier.device.support

/** What asks for background syncs; each syncs a different set of paired devices ([syncTargets]). */
sealed interface SyncTrigger {
    /** The 15-minute periodic work. */
    data object Periodic : SyncTrigger

    /** CDM reports the device at [address] in range. */
    data class Presence(
        val address: String,
    ) : SyncTrigger

    /** The car head unit connected or disconnected; syncs the kinds that live in the car. */
    data object Car : SyncTrigger
}

/** Addresses of the [devices] that [trigger] syncs, in pairing order. */
fun syncTargets(
    trigger: SyncTrigger,
    devices: List<PairedDevice>,
): List<String> =
    when (trigger) {
        SyncTrigger.Periodic -> devices
        is SyncTrigger.Presence -> devices.filter { it.address.equals(trigger.address, ignoreCase = true) }
        SyncTrigger.Car -> devices.filter { it.kind.support.syncsOnCarTrigger }
    }.map { it.address }

/**
 * Enqueues one sync per device a trigger targets; [enqueue] is an automatic (not manual)
 * [WorkControl.syncNow] in the app, since no trigger is the user asking.
 */
class SyncFanOut(
    private val devices: PairedDevices,
    private val enqueue: (address: String) -> Unit,
) {
    /** @return the addresses enqueued. */
    suspend fun fire(trigger: SyncTrigger): List<String> = syncTargets(trigger, devices.all()).onEach(enqueue)
}
