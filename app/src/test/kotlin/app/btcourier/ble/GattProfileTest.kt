package app.btcourier.ble

import com.juul.kable.WriteType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The ring's profile and scan filter. UUIDs from `colmi_r02_client` / Gadgetbridge ColmiR0x:
 * service 6E40FFF0, commands written to 6E400002, answers notified on 6E400003.
 */
class GattProfileTest {
    private val ringService = UUID.fromString("6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E")

    @Test
    fun `the ring profile notifies on 6E400003, writes to 6E400002, needs no bond and no MTU exchange`() {
        val ring = ColmiRingGatt.PROFILE
        assertEquals(ringService, ring.service)
        assertEquals(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), ring.notify)
        assertEquals(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), ring.write)
        assertFalse(ring.wantsBond)
        assertEquals(GattProfile.DEFAULT_MTU, ring.mtu)
    }

    @Test
    fun `ring commands written without response go to 6E400002 without response`() {
        val route = ColmiRingGatt.PROFILE.routeWrite(false)
        assertEquals(
            GattProfile.WriteRoute(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), WriteType.WithoutResponse),
            route,
        )
    }

    @Test
    fun `the ring filter takes an R0x name with or without the brand, or the ring service alone`() {
        val spec = ColmiRingGatt.FILTER
        // Name alone, as PulseLoopiOS's model table names the family.
        assertTrue(spec.matches("R02_0000", emptyList()))
        assertTrue(spec.matches("R06_ABCD", listOf(WicanGatt.SERVICE)))
        assertTrue(spec.matches("COLMI R07_1A2B", emptyList()))
        // Service alone: an unnamed or oddly named ring advertising 6E40FFF0.
        assertTrue(spec.matches(null, listOf(ringService)))
        assertTrue(spec.matches("WiC_0123456789ab", listOf(ringService)))
        // Neither: R1x is another protocol, and the name needs the model digit and the underscore.
        assertFalse(spec.matches("R10_0000", emptyList()))
        assertFalse(spec.matches("COLMI R11_0000", emptyList()))
        assertFalse(spec.matches("R0_0000", emptyList()))
        assertFalse(spec.matches("R02", emptyList()))
        assertFalse(spec.matches("XR02_0000", emptyList()))
        assertFalse(spec.matches("WiC_0123456789ab", listOf(WicanGatt.SERVICE)))
        assertFalse(spec.matches(null, emptyList()))
    }
}
