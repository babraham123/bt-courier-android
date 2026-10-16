package app.btcourier.ui

import app.btcourier.core.elm327.VehicleProfiles
import app.btcourier.core.source.colmi.AutoHeartRate
import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.device.PairingChoices
import app.btcourier.device.ProbeResult
import app.btcourier.fakes.FakeColmiRing
import app.btcourier.fakes.FakeElm327Adapter
import app.btcourier.ui.pairing.PairingPlatform
import app.btcourier.ui.pairing.PairingProbe
import app.btcourier.ui.pairing.PairingStep
import app.btcourier.ui.pairing.PairingViewModel
import app.btcourier.work.DeviceLocks
import app.btcourier.work.LinkFactory
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
 * from the association to the test, which reads its battery and applies the explain step's
 * background heart-rate choice (checked by default) on the same link. An ELM327 skips bonding too,
 * stores the vehicle profile picked on the explain step (generic as none), and its test reads the
 * adapter version and battery voltage. Every association stores its kind.
 * The test opens its link through the link factory under the device lock syncs take, so it
 * reports the device busy, opening nothing, while a sync holds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class PairingViewModelTest {
    private val devices = FakePairedDevices()
    private val ring =
        FakeColmiRing(
            today = LocalDate.of(2026, 10, 15),
            batteryLevel = 42,
            autoHeartRateEnabled = false,
            autoHeartRateIntervalMinutes = 30,
        )
    private val battery = Battery(42, charging = false)
    private val elm327 = FakeElm327Adapter()

    private fun ringResult(autoHeartRate: AutoHeartRate?) =
        ProbeResult.Ring(battery, autoHeartRate, FakeColmiRing.FIRMWARE, FakeColmiRing.HARDWARE)

    private val locks = DeviceLocks()

    private inner class RecordingPlatform : PairingPlatform {
        val observed = mutableListOf<String>()
        val opened = mutableListOf<Pair<String, DeviceKind>>()
        var failOpen = false
        var hangOpen = false
        val openCancelled = CompletableDeferred<Unit>()

        private val links =
            LinkFactory { address, kind ->
                opened += address to kind
                if (failOpen) throw IOException("connect timed out")
                if (hangOpen) {
                    try {
                        awaitCancellation()
                    } finally {
                        openCancelled.complete(Unit)
                    }
                }
                if (kind == DeviceKind.ELM327) elm327.link else ring.link
            }

        // The app's real probe, lock and kind lookup over the fake ring; only the link is faked.
        private val pairingProbe = PairingProbe(links, locks)

        override fun observePresence(address: String): Boolean {
            observed += address
            return true
        }

        override suspend fun probe(
            address: String,
            kind: DeviceKind,
            choices: PairingChoices,
        ): ProbeResult = pairingProbe.run(address, kind, choices)
    }

    private val platform = RecordingPlatform()
    private val stores = VisitStores()
    private val vm = stores.viewModel("visit") { PairingViewModel(devices, platform) }

    private fun pairUpToAssociation(
        kind: DeviceKind,
        address: String,
        name: String?,
        onExplain: () -> Unit = {},
    ) {
        assertEquals(PairingStep.ChooseKind, vm.state.value.step)
        vm.chooseKind(kind)
        assertEquals(PairingStep.Explain, vm.state.value.step)
        onExplain()
        vm.permissionGranted()
        assertEquals(PairingStep.Associate, vm.state.value.step)
        vm.scanning()
        vm.associated(address, name)
    }

    @Test
    fun `a ring skips bonding, starts presence and is stored as a ring`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "aa:bb:cc:dd:ee:02", "R02_0000")

            val s = vm.state.value
            assertEquals(PairingStep.Test, s.step)
            assertEquals("AA:BB:CC:DD:EE:02", s.address)
            assertFalse(s.busy)
            assertEquals(
                listOf(DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING, "R02_0000")),
                devices.remembered,
            )
            assertEquals(listOf("AA:BB:CC:DD:EE:02"), platform.observed)
        }

    @Test
    fun `the ring's connection test reads its battery and turns background heart rate on by default`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
            assertTrue(vm.state.value.choices.autoHeartRate)

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(
                ringResult(AutoHeartRate(enabled = true, intervalMinutes = 5)),
                vm.state.value.probe,
            )
            assertEquals(true to 5, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)
            assertEquals(listOf("AA:BB:CC:DD:EE:02" to DeviceKind.COLMI_RING), platform.opened)
            assertEquals(listOf(0x03, 0x16, 0x16), ring.commands.map { it[0].toInt() })
            assertFalse(vm.state.value.busy)
            assertTrue(ring.link.closed)
            assertFalse(locks.of("AA:BB:CC:DD:EE:02").isLocked, "the lock is released for syncs")
        }

    @Test
    fun `unchecking background heart rate is carried to the test, which only reads the setting`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null) { vm.setAutoHeartRate(false) }
            assertEquals(PairingChoices(autoHeartRate = false), vm.state.value.choices)

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(
                ringResult(AutoHeartRate(enabled = false, intervalMinutes = 30)),
                vm.state.value.probe,
            )
            assertEquals(false to 30, ring.autoHeartRateEnabled to ring.autoHeartRateIntervalMinutes)
            assertEquals(listOf(0x03, 0x16), ring.commands.map { it[0].toInt() })
        }

    @Test
    fun `a ring that does not answer the heart-rate setting still passes the test, unconfirmed`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
            ring.silentCommands += FakeColmiRing.CMD_AUTO_HEART_RATE

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(ringResult(autoHeartRate = null), vm.state.value.probe)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.busy)
        }

    @Test
    fun `a connection test while a sync holds the device reports it busy and opens no link`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
            val syncLock = locks.of("aa:bb:cc:dd:ee:02")
            assertTrue(syncLock.tryLock())

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(ProbeResult.Busy, vm.state.value.probe)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertEquals(emptyList<Any>(), platform.opened)
            assertEquals(emptyList<Any>(), ring.commands)
            assertTrue(syncLock.isLocked, "the sync keeps its lock")

            syncLock.unlock()
            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(
                ringResult(AutoHeartRate(enabled = true, intervalMinutes = 5)),
                vm.state.value.probe,
            )
        }

    @Test
    fun `an ELM327 skips bonding and is stored with the vehicle profile picked on the explain step`() =
        runTest {
            pairUpToAssociation(
                DeviceKind.ELM327,
                "aa:bb:cc:dd:ee:03",
                "vLinker MC+",
            ) { vm.setVehicleProfile("Kia: EV6") }

            assertEquals(PairingStep.Test, vm.state.value.step)
            assertEquals(
                listOf(
                    DeviceConfig("AA:BB:CC:DD:EE:03", DeviceKind.ELM327, "vLinker MC+", vehicleProfile = "Kia: EV6"),
                ),
                devices.remembered,
            )
            assertEquals(listOf("AA:BB:CC:DD:EE:03"), platform.observed)
        }

    @Test
    fun `the generic profile, left as the default or picked back, is stored as none`() =
        runTest {
            pairUpToAssociation(DeviceKind.ELM327, "AA:BB:CC:DD:EE:03", null) {
                assertNull(vm.state.value.choices.vehicleProfile)
                vm.setVehicleProfile("Kia: EV6")
                vm.setVehicleProfile(VehicleProfiles.GENERIC_MODEL)
            }

            assertEquals(listOf(DeviceConfig("AA:BB:CC:DD:EE:03", DeviceKind.ELM327)), devices.remembered)
        }

    @Test
    fun `a vehicle profile is stored only for a kind that offers the picker`() =
        runTest {
            vm.chooseKind(DeviceKind.ELM327)
            vm.setVehicleProfile("Kia: EV6")
            vm.chooseKind(DeviceKind.COLMI_RING)
            vm.permissionGranted()
            vm.scanning()
            vm.associated("AA:BB:CC:DD:EE:02", null)

            assertEquals(listOf(DeviceConfig("AA:BB:CC:DD:EE:02", DeviceKind.COLMI_RING)), devices.remembered)
        }

    @Test
    fun `the ELM327 connection test reports the adapter version and battery voltage and closes the link`() =
        runTest {
            pairUpToAssociation(DeviceKind.ELM327, "AA:BB:CC:DD:EE:03", null)

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals(ProbeResult.Elm327("ELM327 v2.2", 12.6), vm.state.value.probe)
            assertEquals(listOf("AA:BB:CC:DD:EE:03" to DeviceKind.ELM327), platform.opened)
            assertEquals(listOf("ATZ", "ATRV"), elm327.commands)
            assertTrue(elm327.link.closed)
            assertFalse(locks.of("AA:BB:CC:DD:EE:03").isLocked)
        }

    @Test
    fun `a WiCAN bonds first and only then starts presence`() =
        runTest {
            pairUpToAssociation(DeviceKind.WICAN, "AA:BB:CC:DD:EE:01", "WiC_000000000000")

            assertEquals(PairingStep.Bond, vm.state.value.step)
            assertEquals(
                listOf(DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN, "WiC_000000000000")),
                devices.remembered,
            )
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
    fun `leaving the wizard cancels a connection test still running`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
            platform.hangOpen = true
            vm.test()
            testScheduler.advanceUntilIdle()
            assertTrue(vm.state.value.busy)

            stores.keepOnly(null)
            testScheduler.advanceUntilIdle()

            assertTrue(platform.openCancelled.isCompleted)
            assertFalse(locks.of("AA:BB:CC:DD:EE:02").isLocked, "a cancelled test releases the lock")
        }

    @Test
    fun `a failed connection test shows why and can be repeated`() =
        runTest {
            pairUpToAssociation(DeviceKind.COLMI_RING, "AA:BB:CC:DD:EE:02", null)
            platform.failOpen = true

            vm.test()
            testScheduler.advanceUntilIdle()

            assertEquals("Test connection failed: connect timed out", vm.state.value.error)
            assertNull(vm.state.value.probe)
            assertFalse(vm.state.value.busy)
            assertFalse(locks.of("AA:BB:CC:DD:EE:02").isLocked, "a failed test releases the lock")
        }
}
