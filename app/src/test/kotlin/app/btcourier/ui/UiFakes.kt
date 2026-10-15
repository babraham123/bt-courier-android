package app.btcourier.ui

import app.btcourier.ble.PairedDevice
import app.btcourier.ble.PairedDeviceRepository
import app.btcourier.data.AppSettings
import app.btcourier.data.SettingsRepository
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

class RecordingWorkControl : WorkControl {
    var syncs = 0
    val uploads = mutableListOf<Pair<Boolean, Boolean>>()
    val periodic = mutableListOf<Boolean>()

    override fun syncNow() {
        syncs++
    }

    override fun uploadNow(allowCellular: Boolean, replace: Boolean) {
        uploads += allowCellular to replace
    }

    override fun setPeriodicSync(enabled: Boolean) {
        periodic += enabled
    }
}

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    override suspend fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)
}

class FakePairedDevices(var device: PairedDevice?) : PairedDeviceRepository {
    val forgotten = mutableListOf<PairedDevice>()

    override fun current(): PairedDevice? = device

    override fun forget(device: PairedDevice) {
        forgotten += device
        this.device = null
    }
}
