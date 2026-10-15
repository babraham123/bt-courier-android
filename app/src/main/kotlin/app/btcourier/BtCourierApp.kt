package app.btcourier

import android.app.Application
import app.btcourier.ble.CompanionPairedDeviceRepository
import app.btcourier.ble.KableGattLink
import app.btcourier.work.Notifications
import app.btcourier.work.Scheduler
import kotlinx.coroutines.launch

class BtCourierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val paired = CompanionPairedDeviceRepository(this)
        AppGraph.init(
            this,
            links = { address -> KableGattLink.connect(address) },
            paired = { paired.current()?.address },
        )
        Notifications.createChannels(this)
        AppGraph.scope.launch { Scheduler.setPeriodicSync(this@BtCourierApp, AppGraph.settings.current().periodicSync) }
    }
}
