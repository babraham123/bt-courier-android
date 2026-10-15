package app.btcourier.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceConfig
import app.btcourier.data.SettingsRepository
import app.btcourier.ui.status.DiagnosticsExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** `viewModelScope` runs on `Dispatchers.Main`, which the JVM lacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}

/** Gets [visit]'s view model of type [VM] from its store, created by [create], as MainActivity does. */
inline fun <reified VM : ViewModel> VisitStores.viewModel(visit: String, noinline create: () -> VM): VM =
    ViewModelProvider(owner(visit), viewModelFactory { initializer { create() } })[VM::class.java]

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)
}

/** Paired devices in a list; [remember] records configs without changing [devices]. */
class FakePairedDevices(vararg devices: PairedDevice) : PairedDeviceRepository {
    var devices: List<PairedDevice> = devices.toList()
    val remembered = mutableListOf<DeviceConfig>()
    val forgotten = mutableListOf<PairedDevice>()

    override suspend fun all(): List<PairedDevice> = devices

    override suspend fun remember(device: DeviceConfig) {
        remembered += device
    }

    override suspend fun forget(device: PairedDevice) {
        forgotten += device
        devices = devices - device
    }
}

/** A diagnostics export with a fixed text that saves nowhere. */
object NoDiagnostics : DiagnosticsExport {
    override suspend fun text() = "log"

    override suspend fun save(target: Uri, text: String) = Unit
}
