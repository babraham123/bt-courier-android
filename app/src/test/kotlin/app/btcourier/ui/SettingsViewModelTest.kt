package app.btcourier.ui

import app.btcourier.data.AppSettings
import app.btcourier.data.SecretStore
import app.btcourier.data.SinkAuth
import app.btcourier.ui.settings.CacheCapProblem
import app.btcourier.ui.settings.SettingsViewModel
import app.btcourier.ui.settings.SinkUrlProblem
import app.btcourier.work.MapSecretStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class SettingsViewModelTest {
    private val settings = FakeSettings(AppSettings(cacheCapBytes = 200_000_000))
    private val secrets = MapSecretStore()
    private val work = RecordingWorkControl()

    private fun vm() = SettingsViewModel(settings, secrets, work)

    @Test
    fun `the form starts from the stored settings and only says whether a secret exists`() = runTest {
        settings.update {
            it.copy(sinkUrl = "https://sink.invalid/", sinkAuth = SinkAuth.BASIC, sinkUser = "u", carDeviceAddress = CAR)
        }
        secrets.put(SecretStore.UPLOAD_SECRET, "hunter22")

        val state = vm().state.value

        assertTrue(state.loaded)
        assertEquals("https://sink.invalid/", state.form.sinkUrl)
        assertEquals(CAR, state.form.carDeviceAddress)
        assertEquals(SinkAuth.BASIC, state.form.sinkAuth)
        assertEquals("200", state.form.cacheCapMb)
        assertTrue(state.hasStoredSecret)
        assertEquals("", state.form.secretInput)
        assertFalse(state.toString().contains("hunter22"))
    }

    @Test
    fun `saving persists every field, stores the secret apart and re-enqueues the upload`() = runTest {
        val vm = vm()
        vm.edit {
            it.copy(
                sinkUrl = " https://sink.invalid/up/ ",
                sinkAuth = SinkAuth.BASIC,
                sinkUser = "courier",
                secretInput = "s3cret!",
                sinkCompress = false,
                uploadOverCellular = true,
                cacheCapMb = "50",
                periodicSync = false,
                carDeviceAddress = CAR,
            )
        }

        vm.save()

        assertEquals(
            AppSettings(
                sinkUrl = "https://sink.invalid/up/",
                sinkAuth = SinkAuth.BASIC,
                sinkUser = "courier",
                sinkCompress = false,
                uploadOverCellular = true,
                cacheCapBytes = 50_000_000,
                periodicSync = false,
                carDeviceAddress = CAR,
            ),
            settings.current(),
        )
        assertEquals("s3cret!", secrets.get(SecretStore.UPLOAD_SECRET))
        assertEquals(listOf(true to true), work.uploads)
        assertEquals(listOf(false), work.periodic)
        val state = vm.state.value
        assertTrue(state.saved)
        assertTrue(state.hasStoredSecret)
        assertEquals("", state.form.secretInput)
    }

    @Test
    fun `toggling cellular alone replaces the upload with the new network constraint`() = runTest {
        val vm = vm()
        vm.edit { it.copy(uploadOverCellular = true) }

        vm.save()

        assertEquals(listOf(true to true), work.uploads)
        assertEquals(emptyList<Boolean>(), work.periodic)
    }

    @Test
    fun `choosing a car is saved without rescheduling work, and None clears it`() = runTest {
        val vm = vm()
        vm.edit { it.copy(carDeviceAddress = CAR) }
        vm.save()

        assertEquals(CAR, settings.current().carDeviceAddress)
        assertEquals(CAR, vm.state.value.form.carDeviceAddress)

        vm.edit { it.copy(carDeviceAddress = null) }
        vm.save()

        assertEquals(null, settings.current().carDeviceAddress)
        assertEquals(null, vm.state.value.form.carDeviceAddress)
        assertTrue(work.uploads.isEmpty())
        assertTrue(work.periodic.isEmpty())
    }

    @Test
    fun `reloading discards an unsaved car choice`() = runTest {
        settings.update { it.copy(carDeviceAddress = CAR) }
        val vm = vm()
        vm.edit { it.copy(carDeviceAddress = null) }

        vm.reload()

        assertEquals(CAR, vm.state.value.form.carDeviceAddress)
        assertEquals(CAR, settings.current().carDeviceAddress)
    }

    @Test
    fun `saving unchanged settings schedules nothing`() = runTest {
        vm().save()

        assertEquals(emptyList<Pair<Boolean, Boolean>>(), work.uploads)
        assertEquals(emptyList<Boolean>(), work.periodic)
    }

    @Test
    fun `an invalid URL or cache cap is reported and nothing is saved`() = runTest {
        val vm = vm()
        vm.edit { it.copy(sinkUrl = "http://sink.example.org/", cacheCapMb = "lots") }

        vm.save()

        assertEquals(SinkUrlProblem.CLEARTEXT, vm.state.value.urlProblem)
        assertEquals(CacheCapProblem.NOT_A_NUMBER, vm.state.value.capProblem)
        assertFalse(vm.state.value.saved)
        assertEquals(AppSettings(cacheCapBytes = 200_000_000), settings.current())
        assertTrue(work.uploads.isEmpty())
    }

    @Test
    fun `a cache cap outside the allowed range is refused`() = runTest {
        val vm = vm()
        vm.edit { it.copy(cacheCapMb = "5") }

        vm.save()

        assertEquals(CacheCapProblem.OUT_OF_RANGE, vm.state.value.capProblem)
    }

    @Test
    fun `without authentication a typed secret is not stored`() = runTest {
        val vm = vm()
        vm.edit { it.copy(sinkUrl = "https://sink.invalid/", secretInput = "ignored") }

        vm.save()

        assertEquals(null, secrets.get(SecretStore.UPLOAD_SECRET))
    }

    @Test
    fun `clearing the secret deletes it`() = runTest {
        secrets.put(SecretStore.UPLOAD_SECRET, "old")
        val vm = vm()

        vm.clearSecret()

        assertEquals(null, secrets.get(SecretStore.UPLOAD_SECRET))
        assertFalse(vm.state.value.hasStoredSecret)
    }

    private companion object {
        const val CAR = "00:11:22:AA:BB:CC"
    }
}
