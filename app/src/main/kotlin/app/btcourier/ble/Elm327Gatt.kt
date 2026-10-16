package app.btcourier.ble

import java.util.UUID

/**
 * ELM327 BLE adapters bridge the chip's UART to a vendor-chosen GATT service, so the layout is
 * discovered per device at its first link ([DISCOVERY]) and stored with it. Unbonded except the
 * WiCAN's ELM327 service. Writes go as the transport asks (without response), and with response
 * only where the write characteristic declares it. The MTU request is the WiCAN's: an adapter
 * that refuses it keeps 23, and the transport splits commands to fit.
 */
object Elm327Gatt {
    private fun bluetoothBase(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    private val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /**
     * Known layouts, tried in this order (ticket 0002): `FFF0` (most clones and the vLinker
     * family), `FFE0` (HM-10 style, one characteristic both ways), `18F0`, Nordic UART, and the
     * WiCAN's ELM327 service `FEE0`, which needs a bond.
     */
    val CANDIDATES: List<GattLayout> =
        listOf(
            GattLayout(bluetoothBase(0xFFF0), bluetoothBase(0xFFF1), bluetoothBase(0xFFF2)),
            GattLayout(bluetoothBase(0xFFE0), bluetoothBase(0xFFE1), bluetoothBase(0xFFE1)),
            GattLayout(bluetoothBase(0x18F0), bluetoothBase(0x2AF0), bluetoothBase(0x2AF1)),
            GattLayout(NUS_SERVICE, NUS_TX, NUS_RX),
            GattLayout(bluetoothBase(0xFEE0), bluetoothBase(0xFEE3), bluetoothBase(0xFEE1), bonded = true),
        )

    val DISCOVERY =
        GattDiscovery(
            CANDIDATES,
            mtu = WicanGatt.PREFERRED_MTU,
            writeTypeRule = GattProfile.WriteTypeRule.AS_ASKED_IF_SUPPORTED,
        )

    /** The layout to open on a device exposing [services]; see [GattDiscovery.choose]. */
    fun chooseLayout(services: List<DiscoveredService>): GattLayout? = DISCOVERY.choose(services)

    /**
     * Vendor labels in advertised names, found anywhere in the name as the companion-device scan
     * matches, ignoring case: the protocol itself (`ELM327`, `OBDII`, `OBD2`, `OBDLink`, `OBDCheck`),
     * Vgate (`vLinker MC+`, `V-LINK`, `IOS-Vlink`, `Android-Vlink`, `iCar`), Veepeak, Carista,
     * UniCarScan, Konnwei (`KW902`), LELink, PLX Kiwi, Carly, ScanTool, and the WiCAN. Names are
     * what vendors ship, so the list is only ever a convenience: an adapter with another name is
     * still offered by its advertised service.
     */
    val NAME_PATTERN: Regex =
        Regex(
            "(?i).*(elm327|elm ?327|obd|vlinker|v-?link|icar|veepeak|carista|unicarscan|konnwei|kw ?90\\d|lelink|kiwi|carly|scantool|wican).*",
        )

    /**
     * Name or any candidate service: many adapters advertise a generic name or none. A WiCAN Pro
     * matches too (its tunnel service is `FFF0`), which the associate text mentions.
     */
    val FILTER =
        DeviceFilterSpec(
            CANDIDATES.map { UUID.fromString(it.service) }.distinct(),
            NAME_PATTERN,
            DeviceFilterSpec.Match.NAME_OR_SERVICE,
        )
}
