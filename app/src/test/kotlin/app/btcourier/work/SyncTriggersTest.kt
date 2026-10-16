package app.btcourier.work

import app.btcourier.ble.PairedDevice
import app.btcourier.data.DeviceKind
import app.btcourier.device.LiveMode
import app.btcourier.device.support
import app.btcourier.ui.FakePairedDevices
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The trigger rules: which paired devices each trigger syncs (periodic all, presence the one in
 * range, the car WiCANs) and, with drive sessions on, which ones it starts a session for instead,
 * which running sessions the car's disconnect stops, and that a refused start falls back to a sync.
 */
class SyncTriggersTest {
    private val dongle = PairedDevice("AA:BB:CC:DD:EE:01", null, 1, DeviceKind.WICAN)
    private val ring = PairedDevice("AA:BB:CC:DD:EE:02", null, 2, DeviceKind.COLMI_RING)
    private val secondDongle = PairedDevice("AA:BB:CC:DD:EE:03", null, 3, DeviceKind.WICAN)
    private val devices = FakePairedDevices(dongle, ring, secondDongle)

    private val connected = SyncTrigger.Car(connected = true)
    private val disconnected = SyncTrigger.Car(connected = false)

    private val enqueued = mutableListOf<String>()
    private val fanOut = SyncFanOut(devices) { enqueued += it }

    /** Records what the fan-out asks of drive sessions; [refuse] makes those starts fail. */
    private class RecordingStarter(
        var enabled: Boolean = true,
        var running: Set<String> = emptySet(),
        var refuse: Set<String> = emptySet(),
    ) : DriveSessionStarter {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        override suspend fun enabled() = enabled

        override fun running() = running

        override fun start(address: String): Boolean {
            started += address
            return address !in refuse
        }

        override fun carDisconnected(address: String) {
            stopped += address
        }
    }

    private fun plan(
        trigger: SyncTrigger,
        driveSession: Boolean = true,
        running: Set<String> = emptySet(),
        list: List<PairedDevice> = listOf(dongle, ring, secondDongle),
        liveModeOf: (PairedDevice) -> LiveMode = { it.kind.support.liveMode },
    ) = planTrigger(trigger, list, driveSession, running, liveModeOf)

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
    fun `the car connecting or disconnecting enqueues the WiCAN devices only`() =
        runTest {
            fanOut.fire(connected)
            fanOut.fire(disconnected)

            assertEquals(listOf(dongle.address, secondDongle.address, dongle.address, secondDongle.address), enqueued)
        }

    @Test
    fun `with nothing paired no trigger enqueues anything`() =
        runTest {
            devices.devices = emptyList()

            listOf(SyncTrigger.Periodic, connected, disconnected, SyncTrigger.Presence(dongle.address))
                .forEach { fanOut.fire(it) }

            assertEquals(emptyList<String>(), enqueued)
        }

    @Test
    fun `with drive sessions on, the car connecting starts a session for each WiCAN and syncs nothing`() {
        assertEquals(TriggerPlan(sessionStarts = listOf(dongle.address, secondDongle.address)), plan(connected))
    }

    @Test
    fun `with drive sessions on, presence starts a session for a pulled device and syncs one without a live mode`() {
        assertEquals(TriggerPlan(sessionStarts = listOf(dongle.address)), plan(SyncTrigger.Presence(dongle.address)))
        assertEquals(TriggerPlan(syncs = listOf(ring.address)), plan(SyncTrigger.Presence(ring.address)))
    }

    @Test
    fun `with drive sessions off every trigger syncs as before`() {
        assertEquals(
            TriggerPlan(syncs = listOf(dongle.address, secondDongle.address)),
            plan(connected, driveSession = false),
        )
        assertEquals(
            TriggerPlan(syncs = listOf(dongle.address)),
            plan(SyncTrigger.Presence(dongle.address), driveSession = false),
        )
    }

    @Test
    fun `a device already in a session gets nothing from presence, the car connecting or the periodic work`() {
        val running = setOf(dongle.address)

        assertEquals(TriggerPlan(), plan(SyncTrigger.Presence(dongle.address), running = running))
        assertEquals(TriggerPlan(sessionStarts = listOf(secondDongle.address)), plan(connected, running = running))
        assertEquals(
            TriggerPlan(syncs = listOf(ring.address, secondDongle.address)),
            plan(SyncTrigger.Periodic, running = running),
        )
    }

    @Test
    fun `the car disconnecting stops running sessions, even with the setting now off, and syncs the WiCANs without one`() {
        val running = setOf(dongle.address)

        val expected = TriggerPlan(syncs = listOf(secondDongle.address), sessionStops = listOf(dongle.address))
        assertEquals(expected, plan(disconnected, running = running))
        assertEquals(expected, plan(disconnected, driveSession = false, running = running))
    }

    @Test
    fun `an ELM327 is never synced, and starts a session on presence or the car`() {
        val scanner = PairedDevice("AA:BB:CC:DD:EE:04", null, 4, DeviceKind.ELM327)

        assertEquals(
            TriggerPlan(syncs = listOf(dongle.address)),
            plan(SyncTrigger.Periodic, list = listOf(dongle, scanner)),
        )
        assertEquals(
            TriggerPlan(),
            plan(SyncTrigger.Presence(scanner.address), driveSession = false, list = listOf(scanner)),
        )
        assertEquals(TriggerPlan(sessionStarts = listOf(scanner.address)), plan(connected, list = listOf(scanner)))
        assertEquals(
            TriggerPlan(sessionStarts = listOf(scanner.address)),
            plan(SyncTrigger.Presence(scanner.address), list = listOf(scanner)),
        )
        assertEquals(TriggerPlan(), plan(disconnected, list = listOf(scanner)))
    }

    @Test
    fun `the fan-out starts and stops sessions as planned, and a refused start becomes a sync`() =
        runTest {
            val starter = RecordingStarter(refuse = setOf(secondDongle.address))
            val fanOut = SyncFanOut(devices, starter) { enqueued += it }

            assertEquals(listOf(secondDongle.address), fanOut.fire(connected))
            assertEquals(listOf(dongle.address, secondDongle.address), starter.started)

            starter.running = setOf(dongle.address)
            assertEquals(listOf(secondDongle.address), fanOut.fire(disconnected))
            assertEquals(listOf(dongle.address), starter.stopped)
            assertEquals(listOf(secondDongle.address, secondDongle.address), enqueued)
        }

    @Test
    fun `the fan-out reads the setting each time it fires`() =
        runTest {
            val starter = RecordingStarter(enabled = false)
            val fanOut = SyncFanOut(devices, starter) { enqueued += it }

            fanOut.fire(SyncTrigger.Presence(dongle.address))
            starter.enabled = true
            fanOut.fire(SyncTrigger.Presence(dongle.address))

            assertEquals(listOf(dongle.address), enqueued)
            assertEquals(listOf(dongle.address), starter.started)
        }
}
