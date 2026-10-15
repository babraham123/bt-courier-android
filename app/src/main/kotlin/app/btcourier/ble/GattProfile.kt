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
    data class WriteRoute(val characteristic: UUID, val writeType: WriteType)

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
    val PROFILE = GattProfile(ColmiGatt.SERVICE, notify = ColmiGatt.TX, write = ColmiGatt.RX, mtu = GattProfile.DEFAULT_MTU, wantsBond = false)

    /** The family's advertised names start with the model, `R02_...`, `R06_...` (unverified on hardware). */
    val NAME_PATTERN: Regex = Regex("^R0.*")

    val FILTER = DeviceFilterSpec(ColmiGatt.SERVICE, NAME_PATTERN)
}

/** What a companion-device scan must match; kept free of Android types so it can be tested. */
data class DeviceFilterSpec(val serviceUuid: UUID, val namePattern: Regex) {
    fun matches(advertisedName: String?, advertisedServices: Collection<UUID>): Boolean =
        advertisedName != null && namePattern.matches(advertisedName) && serviceUuid in advertisedServices
}
