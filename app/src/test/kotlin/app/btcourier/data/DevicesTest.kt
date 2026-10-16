package app.btcourier.data

import app.btcourier.ble.GattLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The stored device list: an ELM327 entry keeps its vehicle profile and discovered GATT layout
 * (UUID strings); entries written before those fields existed read back unchanged, and entries
 * without them are written as before.
 */
class DevicesTest {
    private val layout =
        GattLayout(
            "0000fee0-0000-1000-8000-00805f9b34fb",
            "0000fee3-0000-1000-8000-00805f9b34fb",
            "0000fee1-0000-1000-8000-00805f9b34fb",
            bonded = true,
        )

    @Test
    fun `an ELM327 entry round trips with its vehicle profile and layout`() {
        val devices =
            listOf(
                DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN, "WiC_000000000000"),
                DeviceConfig("AA:BB:CC:DD:EE:03", DeviceKind.ELM327, "vLinker MC+", "Kia: EV6", layout),
            )

        assertEquals(devices, decodeDevices(encodeDevices(devices)))
    }

    @Test
    fun `the layout is stored as UUID strings under gatt`() {
        val json = encodeDevices(listOf(DeviceConfig("AA:BB:CC:DD:EE:03", DeviceKind.ELM327, gatt = layout)))

        assertEquals(
            """[{"address":"AA:BB:CC:DD:EE:03","kind":"ELM327","name":null,"gatt":""" +
                """{"service":"0000fee0-0000-1000-8000-00805f9b34fb",""" +
                """"notify":"0000fee3-0000-1000-8000-00805f9b34fb",""" +
                """"write":"0000fee1-0000-1000-8000-00805f9b34fb","bonded":true}}]""",
            json,
        )
    }

    @Test
    fun `entries written before vehicle profiles and layouts read with neither, and are written as before`() {
        val old = """[{"address":"AA:BB:CC:DD:EE:02","kind":"COLMI_RING","name":"R02_0000"}]"""

        val devices = decodeDevices(old)

        assertEquals(listOf(DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING, "R02_0000")), devices)
        assertEquals(old, encodeDevices(devices))
    }

    @Test
    fun `an ELM327 without a stored profile or layout reads them as null`() {
        assertEquals(
            listOf(DeviceConfig("AA:BB:CC:DD:EE:03", DeviceKind.ELM327)),
            decodeDevices("""[{"address":"AA:BB:CC:DD:EE:03","kind":"ELM327"}]"""),
        )
    }
}
