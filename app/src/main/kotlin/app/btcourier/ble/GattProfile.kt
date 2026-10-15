package app.btcourier.ble

import app.btcourier.core.source.colmi.ColmiGatt
import com.juul.kable.WriteType
import java.util.UUID

/**
 * Where a [KableGattLink] subscribes and writes: one notify characteristic and one write
 * characteristic in [service], which is all `GattLink` models.
 *
 * @property mtu ATT MTU to ask for after service discovery; [DEFAULT_MTU] or less asks for none.
 * @property wantsBond whether pairing bonds with the device (system passkey dialog) before use.
 */
data class GattProfile(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
    val mtu: Int,
    val wantsBond: Boolean,
) {
    /** One `GattLink.write` routed to its characteristic and ATT write type. */
    data class WriteRoute(
        val characteristic: UUID,
        val writeType: WriteType,
    )

    /** Every write goes to [write]; the caller's flag picks the ATT write type. */
    fun routeWrite(withResponse: Boolean): WriteRoute =
        WriteRoute(write, if (withResponse) WriteType.WithResponse else WriteType.WithoutResponse)

    companion object {
        /** ATT default before any exchange. */
        const val DEFAULT_MTU: Int = 23
    }
}

/**
 * The Colmi R02 family's UART-like service ([ColmiGatt]). The ring has no passkey and is used
 * unbonded, as `colmi_r02_client` and Gadgetbridge do. Its packets are 16 bytes, so the default
 * MTU carries them and none is negotiated. `ColmiRingSource` writes without response.
 */
object ColmiRingGatt {
    val PROFILE =
        GattProfile(
            ColmiGatt.SERVICE,
            notify = ColmiGatt.TX,
            write = ColmiGatt.RX,
            mtu = GattProfile.DEFAULT_MTU,
            wantsBond = false,
        )

    /**
     * The model, optionally after the brand, then `_` and an id: `R02_...`, `COLMI R07_...`
     * (PulseLoopiOS's model table, Gadgetbridge's name patterns). R1x rings speak another protocol
     * and stay out by name. Unverified on hardware.
     */
    val NAME_PATTERN: Regex = Regex("^(COLMI )?R0\\d_.*")

    /**
     * Name or service, as PulseLoopiOS matches: `6E40FFF0` is this protocol's own service rather
     * than a generic UART UUID, so either alone identifies the ring, and a ring that leaves one of
     * them out of its advertising is still offered.
     */
    val FILTER = DeviceFilterSpec(ColmiGatt.SERVICE, NAME_PATTERN, DeviceFilterSpec.Match.NAME_OR_SERVICE)
}

/**
 * What a companion-device scan must match; kept free of Android types so it can be tested.
 *
 * @property match how the name and service conditions combine. Each kind picks its own: a kind
 *   whose service UUID other devices share (the WiCAN's FFF0) needs both.
 */
data class DeviceFilterSpec(
    val serviceUuid: UUID,
    val namePattern: Regex,
    val match: Match,
) {
    enum class Match { NAME_AND_SERVICE, NAME_OR_SERVICE }

    fun matches(
        advertisedName: String?,
        advertisedServices: Collection<UUID>,
    ): Boolean {
        val name = advertisedName != null && namePattern.matches(advertisedName)
        val service = serviceUuid in advertisedServices
        return when (match) {
            Match.NAME_AND_SERVICE -> name && service
            Match.NAME_OR_SERVICE -> name || service
        }
    }
}
