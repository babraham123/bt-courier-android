package app.btcourier.device

import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeColmiRing
import app.btcourier.fakes.FakeGattLink
import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Reply
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.LocalDate

/**
 * Each kind's "test connection" probe over the scripted `ble_http` peripheral and the fake ring,
 * and the ring's heart-rate logging setting the pair wizard writes.
 */
class DeviceProbeTest {
    // Shape from dev_status_manager HTTP_API.md `GET /api/status`, padded past one notify frame.
    private val status =
        """{"bits":{"awake":true,"ble_connected":true},"uptime":"0d 00:01:02",""" +
            """"version":"v0.0.0-test","partition":"ota_0","boot_count":1,"pad":"${"x".repeat(1200)}"}"""

    @Test
    fun `the WiCAN probe reads the firmware version through the tunnel`() =
        runTest {
            val peripheral =
                ScriptedPeripheral(mapOf("/api/status" to Reply(200, status)), mtu = 247, scope = backgroundScope)
            assertEquals(ProbeResult.Wican("v0.0.0-test", 247), DeviceKind.WICAN.support.probe(peripheral.link))
        }

    @Test
    fun `the ring probe reads the battery with one command that asks for a response, leaving its clock alone`() =
        runTest {
            val ring = FakeColmiRing(today = LocalDate.of(2026, 10, 15), batteryLevel = 64, charging = true)

            assertEquals(
                ProbeResult.Ring(Battery(level = 64, charging = true)),
                DeviceKind.COLMI_RING.support.probe(ring.link),
            )

            assertEquals(listOf(0x03), ring.commands.map { it[0].toInt() })
            assertEquals(listOf(true), ring.link.writesWithResponse)
            assertEquals(emptyList<Any>(), ring.clockSets)
        }

    @Test
    fun `a silent ring fails the probe`() =
        runTest {
            assertThrows<IOException> { DeviceKind.COLMI_RING.support.probe(FakeGattLink(mtu = 23)) }
        }

    @Test
    fun `setting the ring's heart-rate logging writes 16 02, on 01 or off 02, the minutes, and waits for its answer`() =
        runTest {
            val ring = FakeColmiRing(today = LocalDate.of(2026, 10, 15), autoHeartRateEnabled = true)

            ColmiRingSupport.setAutoHeartRate(ring.link, enabled = false, intervalMinutes = 30)
            assertEquals(
                listOf(0x16, 0x02, 0x02, 0x1E),
                ring.commands
                    .single()
                    .take(4)
                    .map { it.toInt() and 0xFF },
            )
            assertEquals(false to 30, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)

            ColmiRingSupport.setAutoHeartRate(ring.link, enabled = true, intervalMinutes = 10)
            assertEquals(
                listOf(0x16, 0x02, 0x01, 0x0A),
                ring.commands
                    .last()
                    .take(4)
                    .map { it.toInt() and 0xFF },
            )
            assertEquals(true to 10, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)

            ring.silentCommands += FakeColmiRing.CMD_AUTO_HEART_RATE
            assertThrows<IOException> {
                ColmiRingSupport.setAutoHeartRate(
                    ring.link,
                    enabled = true,
                    intervalMinutes = 5,
                )
            }
        }

    @Test
    fun `a non-200 status fails the probe`() =
        runTest {
            val peripheral = ScriptedPeripheral(emptyMap(), scope = backgroundScope)
            assertThrows<IOException> { DeviceKind.WICAN.support.probe(peripheral.link) }
        }

    @Test
    fun `version parsing tolerates junk`() {
        assertEquals("v1", parseStatusVersion("""{"version":"v1"}""".encodeToByteArray()))
        assertNull(parseStatusVersion("""{"version":""}""".encodeToByteArray()))
        assertNull(parseStatusVersion("""{"uptime":"1d"}""".encodeToByteArray()))
        assertNull(parseStatusVersion("""{"version":{"x":1}}""".encodeToByteArray()))
        assertNull(parseStatusVersion("not json".encodeToByteArray()))
        assertNull(parseStatusVersion("[]".encodeToByteArray()))
    }
}
