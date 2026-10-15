package app.btcourier.ble

import java.util.UUID

/**
 * The WiCAN Pro's GATT contract for the `ble_http` tunnel (meatpi-components
 * `ble_manager/BLE_API.md` §1, §3.2; `ble_http/BLE_HTTP_PROTOCOL.md`).
 *
 * Plain JVM values so routing and discovery rules are unit-testable without a device.
 */
object WicanGatt {
    private fun bluetoothBase(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

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

    /**
     * Notifications on FFF3, every app → device frame on FFF4. Request frames ask for a response
     * (the safe upload mode in §4 of the tunnel protocol); CREDIT and ABORT go without one so
     * paying the device never waits for a connection event. The transport picks which per write.
     * Bonded: the characteristics need an encrypted, MITM-protected link.
     */
    val PROFILE = GattProfile(SERVICE, notify = HTTP_OUT, write = HTTP_IN, mtu = PREFERRED_MTU, wantsBond = true)

    val FILTER = DeviceFilterSpec(SERVICE, NAME_PATTERN)
}
