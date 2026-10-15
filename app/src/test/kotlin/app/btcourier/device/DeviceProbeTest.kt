package app.btcourier.device

import app.btcourier.core.source.colmi.AutoHeartRate
import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeColmiRing
import app.btcourier.fakes.FakeColmiRing.Companion.FIRMWARE
import app.btcourier.fakes.FakeColmiRing.Companion.HARDWARE
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
 * Each kind's "test connection" probe over the scripted `ble_http` peripheral and the fake ring.
 * The ring's probe applies the wizard's background heart-rate choice: checked turns logging on
 * every 5 minutes, unchecked leaves the setting as it was; either way it reads the setting back.
 * It also reads the firmware and hardware revisions from the Device Information Service.
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
            assertEquals(
                ProbeResult.Wican("v0.0.0-test", 247),
                DeviceKind.WICAN.support.probe(peripheral.link, PairingChoices()),
            )
        }

    private fun ringWithLoggingOff(
        firmware: String? = FIRMWARE,
        hardware: String? = HARDWARE,
    ) = FakeColmiRing(
        today = LocalDate.of(2026, 10, 15),
        batteryLevel = 64,
        charging = true,
        autoHeartRateEnabled = false,
        autoHeartRateIntervalMinutes = 30,
        firmware = firmware,
        hardware = hardware,
    )

    private val battery = Battery(level = 64, charging = true)

    /** Command byte and, for `0x16`, its read/write byte, of each command the ring received. */
    private fun FakeColmiRing.commandHeads() =
        commands.map { c -> c.take(if (c[0].toInt() == FakeColmiRing.CMD_AUTO_HEART_RATE) 2 else 1).map { it.toInt() } }

    @Test
    fun `with the box checked the ring probe reads the battery, turns logging on every 5 min and reads it back`() =
        runTest {
            val ring = ringWithLoggingOff()

            assertEquals(
                ProbeResult.Ring(battery, AutoHeartRate(enabled = true, intervalMinutes = 5), FIRMWARE, HARDWARE),
                DeviceKind.COLMI_RING.support.probe(ring.link, PairingChoices(autoHeartRate = true)),
            )

            assertEquals(listOf(listOf(0x03), listOf(0x16, 0x02), listOf(0x16, 0x01)), ring.commandHeads())
            assertEquals(true to 5, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)
            assertEquals(listOf(true, true, true), ring.link.writesWithResponse)
            assertEquals(emptyList<Any>(), ring.clockSets, "setting the clock is the sync's job")
        }

    @Test
    fun `with the box unchecked the ring probe only reads the setting, leaving the ring as it was`() =
        runTest {
            val ring = ringWithLoggingOff()

            assertEquals(
                ProbeResult.Ring(battery, AutoHeartRate(enabled = false, intervalMinutes = 30), FIRMWARE, HARDWARE),
                DeviceKind.COLMI_RING.support.probe(ring.link, PairingChoices(autoHeartRate = false)),
            )

            assertEquals(listOf(listOf(0x03), listOf(0x16, 0x01)), ring.commandHeads())
            assertEquals(false to 30, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)
            assertEquals(emptyList<Any>(), ring.clockSets)
        }

    @Test
    fun `a ring silent on the logging setting still passes the probe, with the setting unconfirmed`() =
        runTest {
            for (checked in listOf(true, false)) {
                val ring = ringWithLoggingOff()
                ring.silentCommands += FakeColmiRing.CMD_AUTO_HEART_RATE

                assertEquals(
                    ProbeResult.Ring(battery, autoHeartRate = null, FIRMWARE, HARDWARE),
                    DeviceKind.COLMI_RING.support.probe(ring.link, PairingChoices(autoHeartRate = checked)),
                )
            }
        }

    @Test
    fun `a revision the ring does not expose is null in the ring probe's result`() =
        runTest {
            val noHardware =
                DeviceKind.COLMI_RING.support.probe(
                    ringWithLoggingOff(hardware = null).link,
                    PairingChoices(),
                )
            assertEquals(FIRMWARE to null, (noHardware as ProbeResult.Ring).firmware to noHardware.hardware)

            val noDis =
                DeviceKind.COLMI_RING.support.probe(
                    ringWithLoggingOff(firmware = null, hardware = null).link,
                    PairingChoices(),
                )
            assertEquals(null to null, (noDis as ProbeResult.Ring).firmware to noDis.hardware)
        }

    @Test
    fun `a silent ring fails the probe`() =
        runTest {
            assertThrows<IOException> {
                DeviceKind.COLMI_RING.support.probe(FakeGattLink(mtu = 23), PairingChoices())
            }
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
            assertThrows<IOException> { DeviceKind.WICAN.support.probe(peripheral.link, PairingChoices()) }
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
