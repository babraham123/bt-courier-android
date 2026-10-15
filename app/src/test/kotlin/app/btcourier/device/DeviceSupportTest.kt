package app.btcourier.device

import app.btcourier.ble.ColmiRingGatt
import app.btcourier.ble.WicanGatt
import app.btcourier.core.source.colmi.ColmiGatt
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeGattLink
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * The kind → [DeviceSupport] lookup: a WiCAN is a bonded `ble_http` dongle found as `WiC_…`, stored
 * under `wican:` and synced when the car connects; a Colmi ring is an unbonded `R0…` device stored
 * under `colmi:` that the car does not sync. Each opens its own source on a link.
 */
class DeviceSupportTest {
    private val options = SourceOptions(clock = { 1_790_000_000_000L }, zone = ZoneOffset.UTC, pageLimit = 7)

    @Test
    fun `a WiCAN bonds to the tunnel service, scans for WiC_ names, stores under wican and syncs with the car`() {
        val wican = DeviceKind.WICAN.support

        assertEquals(WicanGatt.PROFILE, wican.profile)
        assertTrue(wican.profile.wantsBond)
        assertTrue(wican.filter.matches("WiC_0123456789ab", listOf(WicanGatt.SERVICE)))
        assertFalse(wican.filter.matches("R02_0000", listOf(WicanGatt.SERVICE)))
        assertEquals("wican:", wican.sourcePrefix)
        assertTrue(wican.syncsOnCarTrigger)
    }

    @Test
    fun `a ring connects unbonded to its UART service, scans for R0 names, stores under colmi and ignores the car`() {
        val ring = DeviceKind.COLMI_RING.support

        assertEquals(ColmiRingGatt.PROFILE, ring.profile)
        assertFalse(ring.profile.wantsBond)
        assertTrue(ring.filter.matches("R02_0000", listOf(ColmiGatt.SERVICE)))
        assertFalse(ring.filter.matches("WiC_0123456789ab", listOf(ColmiGatt.SERVICE)))
        assertEquals("colmi:", ring.sourcePrefix)
        assertFalse(ring.syncsOnCarTrigger)
    }

    @Test
    fun `every kind has its own source prefix and label`() {
        val supports = DeviceKind.entries.map { it.support }

        assertEquals(supports.size, supports.map { it.sourcePrefix }.toSet().size)
        assertEquals(supports.size, supports.map { it.label }.toSet().size)
    }

    @Test
    fun `each kind opens its own source on a connected link and leaves the link open`() =
        runTest {
            val wicanLink = FakeGattLink()
            val ringLink = FakeGattLink(mtu = 23)

            // Returning at all shows the WiCAN tunnel's reader stopped with the block.
            assertInstanceOf(
                WicanLoggerSource::class.java,
                DeviceKind.WICAN.support.withSource(wicanLink, options) { it },
            )
            assertInstanceOf(
                ColmiRingSource::class.java,
                DeviceKind.COLMI_RING.support.withSource(ringLink, options) { it },
            )

            assertFalse(wicanLink.closed)
            assertFalse(ringLink.closed)
        }
}
