package app.btcourier

import android.app.Application
import app.btcourier.ble.CompanionAssociations
import app.btcourier.ble.KableGattLink
import app.btcourier.device.support
import app.btcourier.work.DevicePresence
import app.btcourier.work.Notifications
import app.btcourier.work.restoreSchedules
import kotlinx.coroutines.launch

class BtCourierApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(
            this,
            links = { address, kind -> KableGattLink.connect(address, kind.support.profile) },
            associations = CompanionAssociations(this),
        )
        Notifications.createChannels(this)
        AppGraph.scope.launch {
            restoreSchedules(AppGraph.settings, AppGraph.pairedDevices, AppGraph.work) {
                DevicePresence.startObserving(this@BtCourierApp, it)
            }
        }
    }
}
