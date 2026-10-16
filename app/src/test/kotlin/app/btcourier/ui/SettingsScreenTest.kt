package app.btcourier.ui

import android.bluetooth.BluetoothManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.btcourier.R
import app.btcourier.data.AppSettings
import app.btcourier.data.SinkConfig
import app.btcourier.data.TransportConfig
import app.btcourier.ui.settings.MAX_CACHE_CAP_MB
import app.btcourier.ui.settings.MIN_CACHE_CAP_MB
import app.btcourier.ui.settings.SettingsScreen
import app.btcourier.ui.settings.SettingsViewModel
import app.btcourier.work.RecordingWorkControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class SettingsScreenTest : ScreenTest() {
    private val sink = SinkConfig("a1", "Archive", transport = TransportConfig.HttpPut("https://sink.invalid/"))
    private val settings = FakeSettings(AppSettings(sinks = listOf(sink), cacheCapBytes = 200_000_000))
    private val work = RecordingWorkControl()
    private val edited = mutableListOf<String?>()

    private fun show() {
        val vm = SettingsViewModel(settings, work)
        setScreen { SettingsScreen(vm, onBack = {}, onEditSink = { edited += it }) }
    }

    private fun stored() = runBlocking { settings.current() }

    /** Bonds `(name, address)` pairs to the phone, as the car picker lists them. */
    private fun bond(vararg devices: Pair<String, String>) {
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val bonded =
            devices.map { (name, address) ->
                adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
            }
        shadowOf(adapter).setBondedDevices(bonded.toSet())
    }

    @Test
    fun `the toggles show the stored settings and saving stores them flipped`() {
        show()

        toggle(R.string.settings_cellular).assertIsOff().tap().assertIsOn()
        toggle(R.string.settings_periodic).assertIsOn().tap().assertIsOff()
        onText(R.string.settings_save).tap()

        onText(R.string.settings_saved).assertIsDisplayed()
        assertEquals(true, stored().uploadOverCellular)
        assertEquals(false, stored().periodicSync)
        assertEquals(listOf(false), work.periodic)
    }

    @Test
    fun `the upload delay starts after each sync and saving stores the one picked`() {
        show()

        field(R.string.settings_upload_delay).assertTextContains(string(R.string.settings_upload_delay_none))
        pick(R.string.settings_upload_delay, R.string.settings_upload_delay_5min)
        onText(R.string.settings_save).tap()

        field(R.string.settings_upload_delay).assertTextContains(string(R.string.settings_upload_delay_5min))
        assertEquals(5 * 60_000L, stored().maxBatchAgeMs)
        assertTrue(work.calls.isEmpty())

        pick(R.string.settings_upload_delay, R.string.settings_upload_delay_30min)
        onText(R.string.settings_save).tap()

        assertEquals(30 * 60_000L, stored().maxBatchAgeMs)
    }

    private fun minutes(n: Int) = app.resources.getQuantityString(R.plurals.settings_minutes, n, n)

    @Test
    fun `the drive session choices show once it is on, start from the defaults, and saving stores the ones picked`() {
        show()

        assertNoText(R.string.settings_drive_interval)
        toggle(R.string.settings_drive_session).assertIsOff().tap().assertIsOn()
        field(R.string.settings_drive_interval).assertTextContains(minutes(2))
        field(R.string.settings_drive_idle).assertTextContains(minutes(10))
        field(R.string.settings_drive_max).assertTextContains(minutes(180))

        pick(R.string.settings_drive_interval, minutes(5))
        pick(R.string.settings_drive_idle, minutes(30))
        pick(R.string.settings_drive_max, minutes(480))
        onText(R.string.settings_save).tap()

        onText(R.string.settings_saved).assertIsDisplayed()
        assertEquals(true, stored().driveSession)
        assertEquals(5, stored().drivePullIntervalMinutes)
        assertEquals(30, stored().driveIdleMinutes)
        assertEquals(480, stored().driveMaxSessionMinutes)
        assertTrue(work.calls.isEmpty())
    }

    @Test
    fun `each sink row shows its name and kind and opens it, and add opens a new one`() {
        show()

        compose.onNode(hasText(sink.name) and hasText(string(R.string.transport_http_put), substring = true)).tap()
        onText(R.string.settings_sink_add).tap()

        assertEquals(listOf(sink.id, null), edited)
    }

    @Test
    fun `a sink's switch disables it at once and replaces the upload`() {
        show()

        compose
            .onNode(isToggleable() and hasAnyAncestor(hasText(sink.name)))
            .assertIsOn()
            .tap()
            .assertIsOff()

        assertFalse(stored().sinks.single().enabled)
        assertEquals(listOf(false to true), work.uploads)
    }

    @Test
    fun `without sinks the list says data stays on the phone`() {
        settings.settings.value = AppSettings()
        show()

        onText(R.string.settings_sinks_empty).assertIsDisplayed()
    }

    @Test
    fun `an out-of-range cache limit names the range and is not saved`() {
        show()

        field(R.string.settings_cache_cap).performTextReplacement("5")
        onText(R.string.settings_save).tap()

        onText(R.string.settings_cache_cap_range, MIN_CACHE_CAP_MB.toString(), MAX_CACHE_CAP_MB.toString())
            .assertIsDisplayed()
        field(R.string.settings_cache_cap).assertIsInError()
        assertEquals(200_000_000L, stored().cacheCapBytes)
    }

    @Test
    fun `the car picker shows the stored car and picks another bonded one`() {
        grantBluetoothConnect()
        bond("Head unit" to HEAD_UNIT, "Other unit" to OTHER_UNIT)
        settings.settings.value = settings.settings.value.copy(carDeviceAddress = HEAD_UNIT)
        show()

        field(R.string.settings_car).assertTextContains("Head unit")
        field(R.string.settings_car).tap()
        menuItem("Other unit").performClick()
        onText(R.string.settings_save).tap()

        field(R.string.settings_car).assertTextContains("Other unit")
        assertEquals(OTHER_UNIT, stored().carDeviceAddress)
    }

    @Test
    fun `without Bluetooth access the stored car still shows, permission is offered, and None clears it`() {
        settings.settings.value = settings.settings.value.copy(carDeviceAddress = HEAD_UNIT)
        show()

        field(R.string.settings_car).assertTextContains(HEAD_UNIT)
        onText(R.string.settings_car_permission).assertExists()
        pick(R.string.settings_car, R.string.settings_car_none)
        onText(R.string.settings_save).tap()

        assertNull(stored().carDeviceAddress)
    }

    private companion object {
        const val HEAD_UNIT = "00:11:22:33:44:55"
        const val OTHER_UNIT = "00:11:22:33:44:66"
    }
}
