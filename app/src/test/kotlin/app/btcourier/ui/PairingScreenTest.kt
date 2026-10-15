package app.btcourier.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import app.btcourier.R
import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.device.ProbeResult
import app.btcourier.ui.pairing.PairingPlatform
import app.btcourier.ui.pairing.PairingScreen
import app.btcourier.ui.pairing.PairingStep
import app.btcourier.ui.pairing.PairingViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingScreenTest : ScreenTest() {
    private val devices = FakePairedDevices()
    private var probeResult: ProbeResult = ProbeResult.Busy
    private val platform = object : PairingPlatform {
        override fun observePresence(address: String) = true

        override suspend fun probe(address: String, kind: DeviceKind) = probeResult
    }
    private val vm = PairingViewModel(devices, platform)
    private var done = 0

    private fun show() = setScreen { PairingScreen(vm, onDone = { done++ }) }

    /**
     * Walks the view model to the test step the way the screen's activity callbacks do. Bonding needs
     * the system dialog, so it is reported done before the screen is shown.
     */
    private fun pairedUpToTest(kind: DeviceKind) {
        vm.chooseKind(kind)
        vm.permissionGranted()
        vm.scanning()
        vm.associated(ADDRESS, null)
        if (vm.state.value.step == PairingStep.Bond) vm.bonded()
    }

    @Test
    fun `the wizard opens by offering every device kind, and picking one explains it`() {
        show()

        onText(R.string.pairing_choose_kind).assertIsDisplayed()
        onText(R.string.kind_wican).assertIsDisplayed()
        onText(R.string.kind_colmi_ring).tap()

        onText(R.string.pairing_explain_ring).assertIsDisplayed()
        onText(R.string.pairing_start).assertIsDisplayed()
    }

    @Test
    fun `with Bluetooth access, start moves on to finding the device`() {
        grantBluetoothConnect()
        show()

        onText(R.string.kind_wican).tap()
        onText(R.string.pairing_start).tap()

        onText(R.string.pairing_associate_body).assertIsDisplayed()
        onText(R.string.pairing_find).assertIsEnabled()
    }

    @Test
    fun `while the scan runs the screen shows progress and find is disabled`() {
        vm.chooseKind(DeviceKind.WICAN)
        vm.permissionGranted()
        vm.scanning()
        show()

        onText(R.string.pairing_working).assertIsDisplayed()
        onText(R.string.pairing_find).assertIsNotEnabled()
    }

    @Test
    fun `a failure shows its message and stops the progress`() {
        vm.chooseKind(DeviceKind.WICAN)
        vm.permissionGranted()
        vm.scanning()
        vm.failed("No device selected.")
        show()

        compose.onNodeWithText("No device selected.").assertIsDisplayed()
        assertNoText(R.string.pairing_working)
        onText(R.string.pairing_find).assertIsEnabled()
    }

    @Test
    fun `a paired ring is stored, its test shows the battery, and done closes the wizard`() {
        probeResult = ProbeResult.Ring(Battery(42, charging = false))
        pairedUpToTest(DeviceKind.COLMI_RING)
        show()

        onText(R.string.pairing_test_body_ring).assertIsDisplayed()
        onText(R.string.pairing_test).tap()
        onText(R.string.pairing_test_ok_ring, 42).assertIsDisplayed()
        onText(R.string.pairing_done).tap()

        assertEquals(listOf(DeviceConfig(ADDRESS, DeviceKind.COLMI_RING, null)), devices.remembered)
        assertEquals(1, done)
    }

    @Test
    fun `a WiCAN test shows its firmware and MTU`() {
        probeResult = ProbeResult.Wican("4.40", 517)
        pairedUpToTest(DeviceKind.WICAN)
        show()

        onText(R.string.pairing_test).tap()

        onText(R.string.pairing_test_ok, "4.40", 517).assertIsDisplayed()
    }

    @Test
    fun `a test while a sync holds the device says to wait`() {
        pairedUpToTest(DeviceKind.WICAN)
        show()

        onText(R.string.pairing_test).tap()

        onText(R.string.pairing_test_busy).assertIsDisplayed()
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:02"
    }
}
