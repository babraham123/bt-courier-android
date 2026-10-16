package app.btcourier.device

import app.btcourier.ble.ColmiRingGatt
import app.btcourier.ble.Elm327Gatt
import app.btcourier.ble.WicanGatt
import app.btcourier.core.elm327.Elm327Source
import app.btcourier.core.source.colmi.ColmiGatt
import app.btcourier.core.source.colmi.ColmiRingSource
import app.btcourier.core.wican.WicanLoggerSource
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeGattLink
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The kind → [DeviceSupport] lookup: a WiCAN is a bonded `ble_http` dongle found as `WiC_…`, stored
 * under `wican:` and synced when the car connects; a Colmi ring is an unbonded `R0…` device stored
 * under `colmi:` that the car does not sync; an ELM327 is an unbonded adapter whose layout is
 * discovered, found by name or service, stored under `elm327:` and not synced by the car. A drive session streams an ELM327 and pulls a WiCAN on its open
 * link, and never holds a ring's. Each opens its own source on a link. A WiCAN and an ELM327 are always due for a sync; a ring is due
 * once the day after its cursor day is whole.
 */
class DeviceSupportTest {
    private val options = SourceOptions(clock = { 1_790_000_000_000L }, zone = ZoneOffset.UTC, pageLimit = 7)

    @Test
    fun `a WiCAN bonds to the tunnel service, scans for WiC_ names, stores under wican, syncs with the car and is pulled in a session`() {
        val wican = DeviceKind.WICAN.support

        assertEquals(WicanGatt.PROFILE, wican.profile)
        assertTrue(wican.profile.wantsBond)
        assertTrue(wican.filter.matches("WiC_0123456789ab", listOf(WicanGatt.SERVICE)))
        assertFalse(wican.filter.matches("R02_0000", listOf(WicanGatt.SERVICE)))
        assertEquals("wican:", wican.sourcePrefix)
        assertTrue(wican.syncsOnCarTrigger)
        assertEquals(LiveMode.PULL, wican.liveMode)
    }

    @Test
    fun `a ring connects unbonded to its UART service, scans for R0 names, stores under colmi and ignores the car and sessions`() {
        val ring = DeviceKind.COLMI_RING.support

        assertEquals(ColmiRingGatt.PROFILE, ring.profile)
        assertFalse(ring.profile.wantsBond)
        assertTrue(ring.filter.matches("R02_0000", listOf(ColmiGatt.SERVICE)))
        assertFalse(ring.filter.matches("WiC_0123456789ab", emptyList()))
        assertEquals("colmi:", ring.sourcePrefix)
        assertFalse(ring.syncsOnCarTrigger)
        assertEquals(LiveMode.NONE, ring.liveMode)
    }

    @Test
    fun `an ELM327 connects unbonded to a discovered layout, scans by name or service, stores under elm327 and ignores the car`() {
        val elm327 = DeviceKind.ELM327.support

        assertEquals(Elm327Gatt.DISCOVERY, elm327.gattDiscovery)
        assertFalse(elm327.profile.wantsBond, "no bond step in the wizard")
        assertTrue(elm327.filter.matches("vLinker MC+", emptyList()))
        assertTrue(elm327.filter.matches(null, listOf(UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"))))
        assertFalse(elm327.filter.matches("R02_0000", listOf(ColmiGatt.SERVICE)))
        assertEquals("elm327:", elm327.sourcePrefix)
        assertFalse(elm327.syncsOnCarTrigger)
        assertNull(DeviceKind.WICAN.support.gattDiscovery)
        assertNull(DeviceKind.COLMI_RING.support.gattDiscovery)
    }

    @Test
    fun `only the ELM327 asks for a vehicle profile at pairing, only the ring for heart rate`() {
        assertNotNull(DeviceKind.ELM327.support.pairingText.vehicleProfileChoice)
        assertNull(DeviceKind.ELM327.support.pairingText.autoHeartRateChoice)
        assertNull(DeviceKind.WICAN.support.pairingText.vehicleProfileChoice)
        assertNull(DeviceKind.COLMI_RING.support.pairingText.vehicleProfileChoice)
    }

    @Test
    fun `an ELM327 has no history to come due, so it never holds a sync back`() {
        assertNull(DeviceKind.ELM327.support.nextDueAtMs(null, ZoneOffset.UTC))
        assertNull(DeviceKind.ELM327.support.nextDueAtMs("live", ZoneOffset.UTC))
    }

    @Test
    fun `a WiCAN is always due, a ring an hour after its cursor's cut`() {
        assertNull(DeviceKind.WICAN.support.nextDueAtMs("1790000000:42", ZoneOffset.UTC))
        assertNull(DeviceKind.WICAN.support.nextDueAtMs(null, ZoneOffset.UTC))
        assertEquals(
            Instant.parse("2026-10-15T10:30:00Z").toEpochMilli(),
            DeviceKind.COLMI_RING.support.nextDueAtMs("2026-10-15@09:30", ZoneOffset.UTC),
        )
        assertEquals(
            Instant.parse("2026-10-14T01:00:00Z").toEpochMilli(),
            DeviceKind.COLMI_RING.support.nextDueAtMs("2026-10-13", ZoneOffset.UTC),
            "a whole day's cut is the midnight that ends it",
        )
        assertNull(DeviceKind.COLMI_RING.support.nextDueAtMs(null, ZoneOffset.UTC), "never synced")
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
            val elm327Link = FakeGattLink()

            // Returning at all shows the WiCAN tunnel's reader stopped with the block.
            assertInstanceOf(
                WicanLoggerSource::class.java,
                DeviceKind.WICAN.support.withSource(wicanLink, options) { it },
            )
            assertInstanceOf(
                ColmiRingSource::class.java,
                DeviceKind.COLMI_RING.support.withSource(ringLink, options) { it },
            )

            // Likewise the ELM327 transport's reader.
            assertInstanceOf(
                Elm327Source::class.java,
                DeviceKind.ELM327.support.withSource(elm327Link, options) { it },
            )

            assertFalse(wicanLink.closed)
            assertFalse(ringLink.closed)
            assertFalse(elm327Link.closed)
        }
}
