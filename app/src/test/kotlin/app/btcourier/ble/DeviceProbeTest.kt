package app.btcourier.ble

import app.btcourier.fakes.ScriptedPeripheral
import app.btcourier.fakes.ScriptedPeripheral.Reply
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** The pair wizard's "test connection" over the scripted `ble_http` peripheral. */
class DeviceProbeTest {
    // Shape from dev_status_manager HTTP_API.md `GET /api/status`, padded past one notify frame.
    private val status = """{"bits":{"awake":true,"ble_connected":true},"uptime":"0d 00:01:02",""" +
        """"version":"v0.0.0-test","partition":"ota_0","boot_count":1,"pad":"${"x".repeat(1200)}"}"""

    @Test
    fun `reads the firmware version through the tunnel`() = runTest {
        val peripheral = ScriptedPeripheral(mapOf("/api/status" to Reply(200, status)), mtu = 247, scope = backgroundScope)
        assertEquals(ProbeResult("v0.0.0-test", 247), probeStatus(peripheral.link))
    }

    @Test
    fun `a non-200 status fails the probe`() = runTest {
        val peripheral = ScriptedPeripheral(emptyMap(), scope = backgroundScope)
        assertThrows<IOException> { probeStatus(peripheral.link) }
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
