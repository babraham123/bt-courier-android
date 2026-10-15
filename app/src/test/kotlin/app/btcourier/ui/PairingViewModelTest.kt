package app.btcourier.ui

import app.btcourier.ble.ProbeResult
import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeColmiRing
import app.btcourier.ble.probe
import app.btcourier.ui.pairing.PairingPlatform
import app.btcourier.ui.pairing.PairingStep
import app.btcourier.ui.pairing.PairingViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException
import java.time.LocalDate

/**
 * The pair wizard's steps per kind: a WiCAN bonds before presence starts, a ring goes straight
 * from the association to the test, which reads its battery. Every association stores its kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class PairingViewModelTest {
    private val devices = FakePairedDevices()
    private val ring = FakeColmiRing(today = LocalDate.of(2026, 10, 15), batteryLevel = 42)

    private inner class RecordingPlatform : PairingPlatform {
        val observed = mutableListOf<String>()
        val probed = mutableListOf<Pair<String, DeviceKind>>()
        var failProbe = false
        var hangProbe = false
        val probeCancelled = CompletableDeferred<Unit>()

        override fun observePresence(address: String): Boolean {
            observed += address
            return true
        }

        // The ring kind probes the fake ring through the app's real probe.
        override suspend fun probe(address: String, kind: DeviceKind): ProbeResult {
            probed += address to kind
            if (failProbe) throw IOException("connect timed out")
            if (hangProbe) {
                try {
                    awaitCancellation()
                } finally {
                    probeCancelled.complete(Unit)
                }
            }
            return probe(ring.link, kind)
        }
    }

    private val platform = RecordingPlatform()
    private val stores = VisitStores()
    private val vm = stores.viewModel("visit") { PairingViewModel(devices, platform) }

    private fun pairUpToAssociation(kind: DeviceKind, address: String, name: String?) {
        assertEquals(PairingStep.ChooseKind, vm.state.value.step)
        vm.chooseKind(kind)
        assertEquals(PairingStep.Explain, vm.state.value.step)
        vm.permissionGranted()
        assertEquals(PairingStep.Associate, vm.state.value.step)
        vm.scanning()
        vm.associated(address, name)
    }

    @Test
    fun `a ring skips bonding, starts presence and is stored as a ring`() = runTest {
        pairUpToAssociation(DeviceKind.COLMI_RING, "aa:bb:cc:dd:ee:02", "R02_0000")

        val s = vm.state.value
        assertEquals(PairingStep.Test, s.step)
        assertEquals("AA:BB:CC:DD:EE:02", s.address)
        assertFalse(s.busy)
        assertEquals(listOf(DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING, "R02_0000")), devices.remembered)
        assertEquals(listOf("AA:BB:CC:DD:EE:02"), platform.observed)
    }

    @Test
    fun `the ring's connection test reads its battery`() = runTest {
        pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)

        vm.test()
        testScheduler.advanceUntilIdle()

        assertEquals(ProbeResult.Ring(Battery(42, charging = false)), vm.state.value.probe)
        assertEquals(listOf("AA:BB:CC:DD:EE:02" to DeviceKind.COLMI_RING), platform.probed)
        assertEquals(listOf(0x03), ring.commands.map { it[0].toInt() })
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a WiCAN bonds first and only then starts presence`() = runTest {
        pairUpToAssociation(DeviceKind.WICAN, "AA:BB:CC:DD:EE:01", "WiC_000000000000")

        assertEquals(PairingStep.Bond, vm.state.value.step)
        assertEquals(listOf(DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN, "WiC_000000000000")), devices.remembered)
        assertEquals(emptyList<String>(), platform.observed, "presence waits for the bond")

        vm.bonding()
        vm.failed("Bonding failed or was cancelled")
        assertEquals(PairingStep.Bond, vm.state.value.step)
        assertEquals("Bonding failed or was cancelled", vm.state.value.error)
        vm.retryBond()
        assertEquals(1, vm.state.value.bondAttempt)

        vm.bonding()
        vm.bonded()

        assertEquals(PairingStep.Test, vm.state.value.step)
        assertNull(vm.state.value.error)
        assertEquals(listOf("AA:BB:CC:DD:EE:01"), platform.observed)
    }

    @Test
    fun `leaving the wizard cancels a connection test still running`() = runTest {
        pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
        platform.hangProbe = true
        vm.test()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.busy)

        stores.keepOnly(null)
        testScheduler.advanceUntilIdle()

        assertTrue(platform.probeCancelled.isCompleted)
    }

    @Test
    fun `a failed connection test shows why and can be repeated`() = runTest {
        pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
        platform.failProbe = true

        vm.test()
        testScheduler.advanceUntilIdle()

        assertEquals("Test connection failed: connect timed out", vm.state.value.error)
        assertNull(vm.state.value.probe)
        assertFalse(vm.state.value.busy)
    }
}
