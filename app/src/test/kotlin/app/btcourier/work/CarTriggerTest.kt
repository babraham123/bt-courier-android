package app.btcourier.work

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarTriggerTest {
    // Literal action strings, as the system sends them, rather than the SDK constants under test.
    private val connected = "android.bluetooth.device.action.ACL_CONNECTED"
    private val disconnected = "android.bluetooth.device.action.ACL_DISCONNECTED"
    private val car = "00:11:22:AA:BB:CC"
    private val now = 1_000_000_000L

    private fun decide(
        action: String? = connected,
        address: String? = car,
        configured: String? = car,
        last: Long? = null,
    ) = decideCarTrigger(action, address, configured, last, now)

    @Test
    fun `the configured car connecting or disconnecting triggers`() {
        assertTrue(decide(action = connected))
        assertTrue(decide(action = disconnected))
    }

    @Test
    fun `another device or another action does not trigger`() {
        assertFalse(decide(address = "00:11:22:AA:BB:CD"))
        assertFalse(decide(address = null))
        assertFalse(decide(action = "android.bluetooth.device.action.BOND_STATE_CHANGED"))
        assertFalse(decide(action = null))
    }

    @Test
    fun `no configured car never triggers`() {
        assertFalse(decide(configured = null))
        assertFalse(decide(configured = ""))
    }

    @Test
    fun `events within 60 s of the last trigger are ignored`() {
        assertFalse(decide(last = now))
        assertFalse(decide(action = disconnected, last = now - 59_999))
        assertTrue(decide(last = now - 60_000))
    }

    @Test
    fun `a last trigger in the future after a clock change does not suppress`() {
        assertTrue(decide(last = now + 5_000))
    }

    @Test
    fun `addresses compare case-insensitively`() {
        assertTrue(decide(address = car.lowercase()))
        assertTrue(decide(configured = car.lowercase()))
    }
}
