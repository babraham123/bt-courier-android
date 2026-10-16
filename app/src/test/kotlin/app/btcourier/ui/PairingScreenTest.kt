package app.btcourier.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import app.btcourier.R
import app.btcourier.core.source.colmi.AutoHeartRate
import app.btcourier.core.source.colmi.Battery
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.device.PairingChoices
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
    private val platform =
        object : PairingPlatform {
            override fun observePresence(address: String) = true

            override suspend fun probe(
                address: String,
                kind: DeviceKind,
                choices: PairingChoices,
            ) = probeResult
        }
    private val vm = PairingViewModel(devices, platform)
    private var done = 0

    private fun show() = setScreen { PairingScreen(vm, onDone = { done++ }) }

    private fun ringResult(
        autoHeartRate: AutoHeartRate? = AutoHeartRate(enabled = true, intervalMinutes = 5),
        firmware: String? = "3.00.10",
        hardware: String? = "V1.0",
    ) = ProbeResult.Ring(Battery(42, charging = false), autoHeartRate, firmware, hardware)

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
        onText(R.string.kind_elm327).assertIsDisplayed()
        onText(R.string.kind_colmi_ring).tap()

        onText(R.string.pairing_explain_ring).assertIsDisplayed()
        onText(R.string.pairing_start).assertIsDisplayed()
    }

    @Test
    fun `the ring's explain step offers background heart rate, checked, and unchecking it is kept`() {
        vm.chooseKind(DeviceKind.COLMI_RING)
        show()

        onText(R.string.pairing_auto_hr_choice_help).assertIsDisplayed()
        toggle(R.string.pairing_auto_hr_choice).assertIsOn().tap()

        toggle(R.string.pairing_auto_hr_choice).assertIsOff()
        assertEquals(PairingChoices(autoHeartRate = false), vm.state.value.choices)
    }

    @Test
    fun `the WiCAN's explain step offers no heart-rate choice`() {
        vm.chooseKind(DeviceKind.WICAN)
        show()

        onText(R.string.pairing_explain_ble).assertIsDisplayed()
        assertNoText(R.string.pairing_auto_hr_choice)
        assertNoText(R.string.pairing_auto_hr_choice_help)
    }

    @Test
    fun `the WiCAN and ring explain steps offer no vehicle profile`() {
        vm.chooseKind(DeviceKind.WICAN)
        show()
        assertNoText(R.string.pairing_vehicle_profile_choice)

        vm.chooseKind(DeviceKind.COLMI_RING)
        compose.waitForIdle()
        assertNoText(R.string.pairing_vehicle_profile_choice)
    }

    @Test
    fun `the ELM327's explain step offers the vehicle profile, generic selected, and the search narrows the list`() {
        vm.chooseKind(DeviceKind.ELM327)
        show()

        onText(R.string.pairing_explain_elm327).assertIsDisplayed()
        onText(R.string.pairing_vehicle_profile_choice).assertIsDisplayed()
        onText(R.string.pairing_vehicle_profile_generic).assertIsSelected()
        assertNoText(R.string.pairing_auto_hr_choice)

        field(R.string.pairing_vehicle_profile_search).performTextInput("ev6")

        assertNoText(R.string.pairing_vehicle_profile_generic)
        compose.onNodeWithText("Kia: EV6").tap().assertIsSelected()
        assertEquals(PairingChoices(vehicleProfile = "Kia: EV6"), vm.state.value.choices)
    }

    @Test
    fun `a search no car model matches says so`() {
        vm.chooseKind(DeviceKind.ELM327)
        show()

        field(R.string.pairing_vehicle_profile_search).performTextInput("no such car")

        onText(R.string.pairing_vehicle_profile_none).assertIsDisplayed()
    }

    @Test
    fun `the vehicle profile picked on the explain step is stored with the associated adapter`() {
        grantBluetoothConnect()
        vm.chooseKind(DeviceKind.ELM327)
        show()

        field(R.string.pairing_vehicle_profile_search).performTextInput("ev6")
        compose.onNodeWithText("Kia: EV6").tap()
        onText(R.string.pairing_start).tap()
        onText(R.string.pairing_associate_body_elm327).assertIsDisplayed()
        vm.scanning()
        vm.associated(ADDRESS, "vLinker MC+")
        compose.waitForIdle()

        assertEquals(listOf(DeviceConfig(ADDRESS, DeviceKind.ELM327, "vLinker MC+", "Kia: EV6")), devices.remembered)
        onText(R.string.pairing_test_body_elm327).assertIsDisplayed()
    }

    @Test
    fun `an ELM327 test shows the adapter version and battery voltage`() {
        probeResult = ProbeResult.Elm327("ELM327 v2.2", 12.6)
        pairedUpToTest(DeviceKind.ELM327)
        show()

        onText(R.string.pairing_test).tap()

        onText(R.string.pairing_test_ok_elm327, "ELM327 v2.2", 12.6).assertIsDisplayed()
        compose.onNodeWithText("ELM327 v2.2, battery 12.6 V").assertIsDisplayed()
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
    fun `a paired ring is stored, its test shows battery, revisions and heart rate, and done closes the wizard`() {
        probeResult = ringResult()
        pairedUpToTest(DeviceKind.COLMI_RING)
        show()

        onText(R.string.pairing_test_body_ring).assertIsDisplayed()
        onText(R.string.pairing_test).tap()
        onText(R.string.pairing_test_ok_ring, 42).assertIsDisplayed()
        onText(R.string.pairing_test_firmware_ring, "3.00.10").assertIsDisplayed()
        onText(R.string.pairing_test_hardware_ring, "V1.0").assertIsDisplayed()
        onText(R.string.pairing_test_auto_hr_on, 5).assertIsDisplayed()
        onText(R.string.pairing_done).tap()

        assertEquals(listOf(DeviceConfig(ADDRESS, DeviceKind.COLMI_RING, null)), devices.remembered)
        assertEquals(1, done)
    }

    @Test
    fun `a ring test says when background heart rate is off or could not be confirmed`() {
        probeResult = ringResult(AutoHeartRate(enabled = false, intervalMinutes = 30))
        pairedUpToTest(DeviceKind.COLMI_RING)
        show()

        onText(R.string.pairing_test).tap()
        onText(R.string.pairing_test_auto_hr_off).assertIsDisplayed()

        probeResult = ringResult(autoHeartRate = null)
        onText(R.string.pairing_test).tap()
        onText(R.string.pairing_test_auto_hr_unknown).assertIsDisplayed()
        assertNoText(R.string.pairing_test_auto_hr_off)
    }

    @Test
    fun `a ring test leaves out a revision the ring does not expose`() {
        probeResult = ringResult(hardware = null)
        pairedUpToTest(DeviceKind.COLMI_RING)
        show()

        onText(R.string.pairing_test).tap()

        onText(R.string.pairing_test_firmware_ring, "3.00.10").assertIsDisplayed()
        assertNoText(string(R.string.pairing_test_hardware_ring, ""), substring = true)
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
