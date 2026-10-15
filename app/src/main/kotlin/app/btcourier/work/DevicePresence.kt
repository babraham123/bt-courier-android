package app.btcourier.work

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DeviceNotAssociatedException
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.btcourier.AppGraph
import kotlinx.coroutines.launch

/**
 * CDM presence: once observing is started for an associated device, the system binds
 * [PresenceService] when the device comes in BLE range, even with the app not running. This is
 * the primary sync trigger; it costs nothing while the device is away.
 */
object DevicePresence {
    private const val TAG = "DevicePresence"

    /**
     * Starts observing [address] (an associated device's MAC). Call it right after pairing (after
     * bonding, for a device that bonds); the system keeps observing across app restarts. Returns
     * false if [address] is not associated.
     */
    fun startObserving(
        context: Context,
        address: String,
    ): Boolean {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                val id = associationId(cdm, address) ?: return false
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address)
            }
            true
        } catch (e: DeviceNotAssociatedException) {
            Log.w(TAG, "presence not observed: device not associated")
            false
        }
    }

    @RequiresApi(33)
    private fun associationId(
        cdm: CompanionDeviceManager,
        address: String,
    ): Int? = cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
}

/**
 * Enqueues a one-time sync of the device that appeared ([SyncTrigger.Presence]). Each API level
 * calls a different callback (31-32 the address one, 33-35 the [AssociationInfo] one, 36+
 * [onDevicePresenceEvent]); a duplicate call is harmless because each device's one-time sync is
 * unique work.
 */
class PresenceService : CompanionDeviceService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) = appeared(address)

    // Abstract on API 31-32, so it must exist even though disappearance needs no action.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) =
        appeared(associationInfo.deviceMacAddress?.toString())

    @Suppress("OVERRIDE_DEPRECATION")
    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = Unit

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED ||
            event.event == DevicePresenceEvent.EVENT_BT_CONNECTED
        ) {
            val cdm = getSystemService(CompanionDeviceManager::class.java)
            appeared(
                cdm
                    ?.myAssociations
                    ?.firstOrNull { it.id == event.associationId }
                    ?.deviceMacAddress
                    ?.toString(),
            )
        }
    }

    private fun appeared(address: String?) {
        if (address == null) return
        AppGraph.scope.launch {
            try {
                AppGraph.syncFanOut.fire(SyncTrigger.Presence(address))
            } catch (e: Exception) {
                Log.w(TAG, "presence sync not enqueued", e)
            }
        }
    }

    private companion object {
        const val TAG = "PresenceService"
    }
}
