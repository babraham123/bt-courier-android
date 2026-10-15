package app.btcourier.ble

import com.juul.kable.WriteType
import java.util.UUID

/**
 * The WiCAN Pro's GATT contract for the `ble_http` tunnel (meatpi-components
 * `ble_manager/BLE_API.md` §1, §3.2; `ble_http/BLE_HTTP_PROTOCOL.md`).
 *
 * Plain JVM values so routing and discovery rules are unit-testable without a device.
 */
object WicanGatt {
    private fun bluetoothBase(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    /** Advertised in the primary advertising PDU, so it is the one field every scan sees. */
    val SERVICE: UUID = bluetoothBase(0xFFF0)

    /** HTTP OUT: device → app frames, notify or indicate; the CCCD value chooses the mode. */
    val HTTP_OUT: UUID = bluetoothBase(0xFFF3)

    /** HTTP IN: app → device frames, write or write-without-response. */
    val HTTP_IN: UUID = bluetoothBase(0xFFF4)

    /**
     * `WiC_` + the 12 lowercase hex digits of the SoftAP MAC. FFF0 alone is shared by many
     * generic BLE UART modules, so discovery also needs the name to avoid offering strangers.
     */
    val NAME_PATTERN: Regex = Regex("^WiC_[0-9a-f]{12}$")

    /** The device prefers 517 (§6); Android must ask for it, iOS negotiates on its own. */
    const val PREFERRED_MTU: Int = 517

    /** ATT default before any exchange. */
    const val DEFAULT_MTU: Int = 23

    /** One `GattLink.write` routed to its characteristic and ATT write type. */
    data class WriteRoute(val characteristic: UUID, val writeType: WriteType)

    /**
     * Every app → device frame goes to FFF4. Request frames ask for a response (the safe upload
     * mode in §4 of the tunnel protocol); CREDIT and ABORT go without one so paying the device
     * never waits for a connection event. The transport picks which; this only maps the flag.
     */
    fun routeWrite(withResponse: Boolean): WriteRoute =
        WriteRoute(HTTP_IN, if (withResponse) WriteType.WithResponse else WriteType.WithoutResponse)
}

/** What a companion-device scan must match; kept free of Android types so it can be tested. */
data class DeviceFilterSpec(val serviceUuid: UUID, val namePattern: Regex) {
    fun matches(advertisedName: String?, advertisedServices: Collection<UUID>): Boolean =
        advertisedName != null && namePattern.matches(advertisedName) && serviceUuid in advertisedServices

    companion object {
        val WICAN = DeviceFilterSpec(WicanGatt.SERVICE, WicanGatt.NAME_PATTERN)
    }
}
