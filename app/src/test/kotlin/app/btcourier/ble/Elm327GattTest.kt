package app.btcourier.ble

import com.juul.kable.WriteType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ELM327 layout discovery and scan filter (ticket 0002): the first known pair present, in the
 * order `FFF0` (`FFF1` notify / `FFF2` write), `FFE0` (`FFE1` both), `18F0` (`2AF0` / `2AF1`),
 * Nordic UART (`6E400003` / `6E400002`), WiCAN `FEE0` (`FEE3` / `FEE1`, bonded); else the first
 * service with a notifying and a writable characteristic. The scan takes a brand or protocol name
 * or any of those services.
 */
class Elm327GattTest {
    private fun short(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))

    private val nus = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val nusTx = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val nusRx = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    private fun notifying(uuid: UUID) = DiscoveredCharacteristic(uuid, notify = true, write = false)

    private fun writable(uuid: UUID) = DiscoveredCharacteristic(uuid, notify = false, write = true)

    private fun both(uuid: UUID) = DiscoveredCharacteristic(uuid, notify = true, write = true)

    private fun service(
        uuid: UUID,
        vararg characteristics: DiscoveredCharacteristic,
    ) = DiscoveredService(uuid, characteristics.toList())

    // What every device exposes besides its UART: GAP (name, read only) and GATT (Service Changed, indicate).
    private val standard =
        listOf(
            service(short(0x1800), DiscoveredCharacteristic(short(0x2A00), notify = false, write = false)),
            service(short(0x1801), notifying(short(0x2A05))),
        )

    private val fff0 = service(short(0xFFF0), notifying(short(0xFFF1)), writable(short(0xFFF2)))
    private val ffe0 = service(short(0xFFE0), both(short(0xFFE1)))
    private val x18f0 = service(short(0x18F0), notifying(short(0x2AF0)), writable(short(0x2AF1)))
    private val uart = service(nus, writable(nusRx), notifying(nusTx))
    private val fee0 = service(short(0xFEE0), writable(short(0xFEE1)), notifying(short(0xFEE3)))

    @Test
    fun `each known layout is found on its own`() {
        assertEquals(GattLayout(short(0xFFF0), short(0xFFF1), short(0xFFF2)), Elm327Gatt.chooseLayout(standard + fff0))
        assertEquals(GattLayout(short(0xFFE0), short(0xFFE1), short(0xFFE1)), Elm327Gatt.chooseLayout(standard + ffe0))
        assertEquals(GattLayout(short(0x18F0), short(0x2AF0), short(0x2AF1)), Elm327Gatt.chooseLayout(standard + x18f0))
        assertEquals(GattLayout(nus, nusTx, nusRx), Elm327Gatt.chooseLayout(standard + uart))
        assertEquals(
            GattLayout(short(0xFEE0), short(0xFEE3), short(0xFEE1), bonded = true),
            Elm327Gatt.chooseLayout(standard + fee0),
        )
    }

    @Test
    fun `with several known layouts present the earlier one in the ticket's order wins, whatever the discovery order`() {
        assertEquals(short(0xFFF0).toString(), Elm327Gatt.chooseLayout(standard + fee0 + uart + ffe0 + fff0)?.service)
        assertEquals(short(0xFFE0).toString(), Elm327Gatt.chooseLayout(standard + fee0 + uart + x18f0 + ffe0)?.service)
        assertEquals(nus.toString(), Elm327Gatt.chooseLayout(standard + fee0 + uart)?.service)
    }

    @Test
    fun `a WiCAN's FFF0 tunnel lacks FFF1 and FFF2, so its bonded FEE0 service is picked`() {
        val tunnel = service(short(0xFFF0), notifying(short(0xFFF3)), writable(short(0xFFF4)))

        assertEquals(
            GattLayout(short(0xFEE0), short(0xFEE3), short(0xFEE1), bonded = true),
            Elm327Gatt.chooseLayout(standard + tunnel + fee0),
        )
    }

    @Test
    fun `a known service whose characteristics cannot notify or take writes does not count`() {
        val readOnly = service(short(0xFFF0), writable(short(0xFFF1)), notifying(short(0xFFF2)))

        assertEquals(
            GattLayout(short(0xFFE0), short(0xFFE1), short(0xFFE1)),
            Elm327Gatt.chooseLayout(standard + readOnly + ffe0),
        )
    }

    @Test
    fun `without a known layout the first service with a notifying and a writable characteristic is used`() {
        val vendor = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
        val tx = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
        val rx = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
        val other = UUID.fromString("0000abe0-0000-1000-8000-00805f9b34fb")

        assertEquals(
            GattLayout(vendor, tx, rx),
            Elm327Gatt.chooseLayout(
                standard + service(vendor, writable(rx), notifying(tx)) + service(other, both(short(0xABE1))),
            ),
        )
        // One characteristic both ways, and a separate writable one preferred over it.
        assertEquals(
            GattLayout(other, short(0xABE1), short(0xABE1)),
            Elm327Gatt.chooseLayout(standard + service(other, both(short(0xABE1)))),
        )
        assertEquals(
            GattLayout(other, short(0xABE1), short(0xABE2)),
            Elm327Gatt.chooseLayout(standard + service(other, both(short(0xABE1)), writable(short(0xABE2)))),
        )
    }

    @Test
    fun `a device with no notifying and writable pair in one service has no layout`() {
        assertNull(Elm327Gatt.chooseLayout(standard))
        assertNull(Elm327Gatt.chooseLayout(emptyList()))
        assertNull(
            Elm327Gatt.chooseLayout(
                standard + service(short(0xABC0), notifying(short(0xABC1))) +
                    service(short(0xABD0), writable(short(0xABD1))),
            ),
        )
    }

    @Test
    fun `a layout opens with MTU 517, its bond flag, and writes without response unless asked and declared`() {
        val layout = GattLayout(short(0xFFF0), short(0xFFF1), short(0xFFF2))
        val profile = Elm327Gatt.DISCOVERY.profile(layout)

        assertEquals(short(0xFFF0), profile.service)
        assertEquals(short(0xFFF1), profile.notify)
        assertEquals(short(0xFFF2), profile.write)
        assertEquals(517, profile.mtu)
        assertFalse(profile.wantsBond)
        assertTrue(Elm327Gatt.DISCOVERY.profile(layout.copy(bonded = true)).wantsBond)
        assertEquals(
            GattProfile.WriteRoute(short(0xFFF2), WriteType.WithoutResponse),
            profile.routeWrite(withResponse = false, writeWithResponseSupported = true),
        )
        assertEquals(
            GattProfile.WriteRoute(short(0xFFF2), WriteType.WithoutResponse),
            profile.routeWrite(withResponse = true, writeWithResponseSupported = false),
        )
    }

    @Test
    fun `the scan takes vLinker, V-LINK, OBD or ELM327 anywhere in the name, ignoring case, or a known service`() {
        val spec = Elm327Gatt.FILTER
        for (name in listOf(
            "vLinker MC+",
            "VLINKER FS",
            "V-LINK",
            "vlink",
            "OBDII",
            "My OBD",
            "ELM327 v1.5",
            "iCar elm327",
        )) {
            assertTrue(spec.matches(name, emptyList()), name)
        }
        for (uuid in listOf(short(0xFFF0), short(0xFFE0), short(0x18F0), nus, short(0xFEE0))) {
            assertTrue(spec.matches(null, listOf(uuid)), uuid.toString())
        }
        assertFalse(spec.matches("R02_0000", emptyList()))
        assertFalse(spec.matches(null, listOf(short(0x180D))))
    }
}
