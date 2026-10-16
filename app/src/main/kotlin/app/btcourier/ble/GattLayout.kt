package app.btcourier.ble

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One serial-bridge layout: notifications from [notify], writes to [write], both in [service]
 * ([notify] and [write] may be the same characteristic). Stored per device in
 * [app.btcourier.data.DeviceConfig.gatt], hence UUID strings.
 *
 * @property bonded whether the characteristics need an encrypted link, so Android bonds (system
 *   pairing dialog) on first access.
 */
@Serializable
data class GattLayout(
    val service: String,
    val notify: String,
    val write: String,
    val bonded: Boolean = false,
) {
    constructor(service: UUID, notify: UUID, write: UUID, bonded: Boolean = false) :
        this(service.toString(), notify.toString(), write.toString(), bonded)

    /**
     * Whether [services] has [service] with [notify] able to notify (or indicate) and [write]
     * able to take writes.
     */
    internal fun presentIn(services: List<DiscoveredService>): Boolean {
        val found = services.firstOrNull { it.uuid == uuid(service) } ?: return false
        val notifies = found.characteristics.any { it.uuid == uuid(notify) && it.notify }
        val writes = found.characteristics.any { it.uuid == uuid(write) && it.write }
        return notifies && writes
    }
}

private fun uuid(text: String): UUID = UUID.fromString(text)

/**
 * A characteristic as service discovery reported it, without Kable types.
 *
 * @property notify declares notify or indicate.
 * @property write declares write or write without response.
 */
data class DiscoveredCharacteristic(
    val uuid: UUID,
    val notify: Boolean,
    val write: Boolean,
)

/** A discovered service and its characteristics, in discovery order. */
data class DiscoveredService(
    val uuid: UUID,
    val characteristics: List<DiscoveredCharacteristic>,
)

/**
 * How a kind whose GATT layout varies by model finds each device's: the first of [candidates]
 * present on the device, else the first service with a notifying and a writable characteristic.
 * The link adapter connects without a profile, discovers, and opens [profile] of what [choose]
 * picked; the pick is stored with the device so later links skip the search.
 *
 * @property mtu what the link asks for after discovery, whatever the layout.
 */
data class GattDiscovery(
    val candidates: List<GattLayout>,
    val mtu: Int,
    val writeTypeRule: GattProfile.WriteTypeRule,
) {
    /**
     * The layout to open on a device exposing [services]; null when none fits. The fallback
     * prefers a writable characteristic other than the notifying one (the usual TX/RX pair), and
     * takes the notifying one itself when it is also the only writable one (HM-10 style).
     */
    fun choose(services: List<DiscoveredService>): GattLayout? {
        candidates.firstOrNull { it.presentIn(services) }?.let { return it }
        for (service in services) {
            val notify = service.characteristics.firstOrNull { it.notify } ?: continue
            val write =
                service.characteristics.firstOrNull { it.write && it.uuid != notify.uuid }
                    ?: notify.takeIf { it.write }
                    ?: continue
            return GattLayout(service.uuid, notify.uuid, write.uuid)
        }
        return null
    }

    fun profile(layout: GattLayout): GattProfile =
        GattProfile(
            uuid(layout.service),
            notify = uuid(layout.notify),
            write = uuid(layout.write),
            mtu = mtu,
            wantsBond = layout.bonded,
            writeTypeRule = writeTypeRule,
        )
}
