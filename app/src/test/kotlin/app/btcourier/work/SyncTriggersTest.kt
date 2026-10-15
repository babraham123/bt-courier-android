package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.data.DeviceKind
import app.btcourier.ui.FakePairedDevices
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which paired devices each trigger syncs: periodic all, presence the one in range, the car WiCANs. */
class SyncTriggersTest {
    private val dongle = PairedDevice("AA:BB:CC:DD:EE:01", null, 1, DeviceKind.WICAN)
    private val ring = PairedDevice("AA:BB:CC:DD:EE:02", null, 2, DeviceKind.COLMI_RING)
    private val secondDongle = PairedDevice("AA:BB:CC:DD:EE:03", null, 3, DeviceKind.WICAN)
    private val devices = FakePairedDevices(dongle, ring, secondDongle)

    private val enqueued = mutableListOf<String>()
    private val fanOut = SyncFanOut(devices) { enqueued += it }

    @Test
    fun `the periodic sync enqueues every paired device, in pairing order`() =
        runTest {
            assertEquals(listOf(dongle, ring, secondDongle).map { it.address }, fanOut.fire(SyncTrigger.Periodic))
            assertEquals(listOf(dongle, ring, secondDongle).map { it.address }, enqueued)
        }

    @Test
    fun `presence enqueues only the device that appeared, matched case-insensitively`() =
        runTest {
            fanOut.fire(SyncTrigger.Presence("aa:bb:cc:dd:ee:02"))

            assertEquals(listOf(ring.address), enqueued)
        }

    @Test
    fun `presence of an address that is not paired enqueues nothing`() =
        runTest {
            assertEquals(emptyList<String>(), fanOut.fire(SyncTrigger.Presence("AA:BB:CC:DD:EE:09")))
            assertEquals(emptyList<String>(), enqueued)
        }

    @Test
    fun `the car enqueues the WiCAN devices only`() =
        runTest {
            fanOut.fire(SyncTrigger.Car)

            assertEquals(listOf(dongle.address, secondDongle.address), enqueued)
        }

    @Test
    fun `with nothing paired no trigger enqueues anything`() =
        runTest {
            devices.devices = emptyList()

            listOf(
                SyncTrigger.Periodic,
                SyncTrigger.Car,
                SyncTrigger.Presence(dongle.address),
            ).forEach { fanOut.fire(it) }

            assertEquals(emptyList<String>(), enqueued)
        }
}
