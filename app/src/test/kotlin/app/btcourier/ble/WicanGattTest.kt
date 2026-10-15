package app.btcourier.ble

import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Expected values come from meatpi-components `ble_manager/BLE_API.md` §1, §3.2. */
class WicanGattTest {
    @Test
    fun `characteristics sit on the Bluetooth base UUID`() {
        assertEquals(UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"), WicanGatt.SERVICE)
        assertEquals(UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB"), WicanGatt.HTTP_OUT)
        assertEquals(UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB"), WicanGatt.HTTP_IN)
    }

    @Test
    fun `request frames are written to FFF4 with response`() {
        assertEquals(WicanGatt.WriteRoute(WicanGatt.HTTP_IN, WriteType.WithResponse), WicanGatt.routeWrite(true))
    }

    @Test
    fun `credit and abort frames are written to FFF4 without response`() {
        assertEquals(WicanGatt.WriteRoute(WicanGatt.HTTP_IN, WriteType.WithoutResponse), WicanGatt.routeWrite(false))
    }

    @Test
    fun `nothing is ever written to the notify characteristic`() {
        listOf(true, false).forEach { assertTrue(WicanGatt.routeWrite(it).characteristic != WicanGatt.HTTP_OUT) }
    }

    @Test
    fun `name pattern is WiC_ plus 12 lowercase hex`() {
        val name = WicanGatt.NAME_PATTERN
        assertTrue(name.matches("WiC_0123456789ab"))
        assertFalse(name.matches("WiC_0123456789AB"), "device_id is lowercase hex")
        assertFalse(name.matches("WiC_0123456789a"), "11 digits")
        assertFalse(name.matches("WiC_0123456789abc"), "13 digits")
        assertFalse(name.matches("WiCAN_0123456789ab"))
        assertFalse(name.matches("xWiC_0123456789ab"))
    }

    @Test
    fun `companion filter needs both the FFF0 service and the name`() {
        val spec = DeviceFilterSpec.WICAN
        assertEquals(WicanGatt.SERVICE, spec.serviceUuid)
        assertTrue(spec.matches("WiC_0123456789ab", listOf(WicanGatt.SERVICE)))
        // FFF0 is common on generic BLE UART modules; the name keeps them out of the chooser.
        assertFalse(spec.matches("HM-10", listOf(WicanGatt.SERVICE)))
        assertFalse(spec.matches("WiC_0123456789ab", emptyList()))
        assertFalse(spec.matches(null, listOf(WicanGatt.SERVICE)))
    }

    @Test
    fun `kable states map to link states`() {
        assertEquals(LinkState.Connecting, State.Connecting.Bluetooth.toLinkState())
        assertEquals(LinkState.Connecting, State.Connecting.Services.toLinkState())
        assertEquals(LinkState.Connecting, State.Connecting.Observes.toLinkState())
        assertEquals(LinkState.Connected, State.Connected(TestScope()).toLinkState())
        assertEquals(LinkState.Disconnecting, State.Disconnecting.toLinkState())
        assertEquals(LinkState.Disconnected(null), State.Disconnected().toLinkState())
        assertEquals(
            LinkState.Disconnected("Peripheral Disconnected"),
            State.Disconnected(State.Disconnected.Status.PeripheralDisconnected).toLinkState(),
        )
    }

    @Test
    fun `newest association wins`() {
        val older = PairedDevice("AA:00:00:00:00:01", "WiC_a", 3)
        val newer = PairedDevice("AA:00:00:00:00:02", "WiC_b", 7)
        assertEquals(newer, selectPaired(listOf(newer, older)))
        assertNull(selectPaired(emptyList()))
        // Pre-API-33 associations have no id; the list is in creation order.
        val legacy = listOf(PairedDevice("AA:00:00:00:00:01", null, null), PairedDevice("AA:00:00:00:00:02", null, null))
        assertEquals(legacy.last(), selectPaired(legacy))
    }
}
