package app.btcourier.ui

import app.btcourier.data.AppSettings
import app.btcourier.data.SinkConfig
import app.btcourier.data.TransportConfig
import app.btcourier.ui.settings.CacheCapProblem
import app.btcourier.ui.settings.SettingsViewModel
import app.btcourier.ui.settings.validateCacheCap
import app.btcourier.work.RecordingWorkControl
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class SettingsViewModelTest {
    private val sink = SinkConfig("a1", "Archive", transport = TransportConfig.HttpPut("https://sink.invalid/"))
    private val settings = FakeSettings(AppSettings(sinks = listOf(sink), cacheCapBytes = 200_000_000))
    private val work = RecordingWorkControl()

    private fun vm() = SettingsViewModel(settings, work)

    @Test
    fun `the form starts from the stored settings and lists the sinks`() = runTest {
        settings.update { it.copy(carDeviceAddress = CAR) }

        val state = vm().state.value

        assertTrue(state.loaded)
        assertEquals(CAR, state.form.carDeviceAddress)
        assertEquals("200", state.form.cacheCapMb)
        assertEquals(listOf(sink), state.sinks)
    }

    @Test
    fun `saving persists every general field, leaves the sinks alone and re-enqueues the upload`() = runTest {
        val vm = vm()
        vm.edit { it.copy(uploadOverCellular = true, cacheCapMb = " 50 ", periodicSync = false, carDeviceAddress = CAR) }

        vm.save()

        assertEquals(
            AppSettings(sinks = listOf(sink), uploadOverCellular = true, cacheCapBytes = 50_000_000, periodicSync = false, carDeviceAddress = CAR),
            settings.current(),
        )
        assertEquals(listOf(true to true), work.uploads)
        assertEquals(listOf(false), work.periodic)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `disabling a sink is saved at once and replaces the queued upload`() = runTest {
        val vm = vm()

        vm.setSinkEnabled("a1", false)

        assertEquals(listOf(sink.copy(enabled = false)), settings.current().sinks)
        assertEquals(listOf(sink.copy(enabled = false)), vm.state.value.sinks)
        assertEquals(listOf(false to true), work.uploads)
    }

    @Test
    fun `choosing a car is saved without rescheduling work, and None clears it`() = runTest {
        val vm = vm()
        vm.edit { it.copy(carDeviceAddress = CAR) }
        vm.save()

        assertEquals(CAR, settings.current().carDeviceAddress)

        vm.edit { it.copy(carDeviceAddress = null) }
        vm.save()

        assertEquals(null, settings.current().carDeviceAddress)
        assertTrue(work.uploads.isEmpty())
        assertTrue(work.periodic.isEmpty())
    }

    @Test
    fun `reloading discards unsaved edits`() = runTest {
        settings.update { it.copy(carDeviceAddress = CAR) }
        val vm = vm()
        vm.edit { it.copy(carDeviceAddress = null) }

        vm.reload()

        assertEquals(CAR, vm.state.value.form.carDeviceAddress)
    }

    @Test
    fun `saving unchanged settings schedules nothing`() = runTest {
        vm().save()

        assertEquals(emptyList<Pair<Boolean, Boolean>>(), work.uploads)
        assertEquals(emptyList<Boolean>(), work.periodic)
    }

    @Test
    fun `an invalid cache cap is reported and nothing is saved`() = runTest {
        val vm = vm()
        vm.edit { it.copy(cacheCapMb = "lots", uploadOverCellular = true) }

        vm.save()

        assertEquals(CacheCapProblem.NOT_A_NUMBER, vm.state.value.capProblem)
        assertFalse(vm.state.value.saved)
        assertFalse(settings.current().uploadOverCellular)

        vm.edit { it.copy(cacheCapMb = "5") }
        vm.save()
        assertEquals(CacheCapProblem.OUT_OF_RANGE, vm.state.value.capProblem)
    }

    @Test
    fun `the cache cap accepts 10 to 2000 MB inclusive`() {
        assertEquals(CacheCapProblem.OUT_OF_RANGE, validateCacheCap("9"))
        assertNull(validateCacheCap("10"))
        assertNull(validateCacheCap("2000"))
        assertEquals(CacheCapProblem.OUT_OF_RANGE, validateCacheCap("2001"))
    }

    private companion object {
        const val CAR = "00:11:22:AA:BB:CC"
    }
}
