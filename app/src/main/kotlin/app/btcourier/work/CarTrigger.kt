package app.btcourier.work

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.btcourier.AppGraph
import kotlinx.coroutines.launch

/** Car events closer together than this collapse into one trigger (head units often reconnect). */
const val CAR_TRIGGER_DEBOUNCE_MS = 60_000L

/**
 * True when an ACL broadcast should enqueue a sync: [action] is a connect or disconnect, [address]
 * is the configured car (MACs compare case-insensitively), and the last trigger is at least
 * [CAR_TRIGGER_DEBOUNCE_MS] old. A [lastTriggerMs] in the future (wall clock moved back) does not
 * suppress, so a clock change can never silence the trigger for good.
 */
fun decideCarTrigger(action: String?, address: String?, configuredAddress: String?, lastTriggerMs: Long?, nowMs: Long): Boolean {
    if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return false
    if (address.isNullOrBlank() || configuredAddress.isNullOrBlank()) return false
    if (!address.equals(configuredAddress, ignoreCase = true)) return false
    val sinceLast = lastTriggerMs?.let { nowMs - it } ?: return true
    return sinceLast !in 0 until CAR_TRIGGER_DEBOUNCE_MS
}

/**
 * Car head unit ACL events (plan, "Car trigger"); a secondary trigger next to CDM presence.
 *
 * Manifest-declared on purpose: ACL_CONNECTED/DISCONNECTED are on the implicit-broadcast exception
 * list, so they still reach a manifest receiver under the API 26+ background limits, whatever the
 * target SDK. Source: developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
 * ("User experience is not likely to suffer if apps receive broadcasts for these Bluetooth events").
 *
 * On API 31+ the system sends these broadcasts only to holders of BLUETOOTH_CONNECT, so the trigger
 * stays silent until that runtime permission is granted.
 *
 * - Connect: the phone is in the car, so the dongle is awake; sync now.
 * - Disconnect: the trip just ended. The dongle stays awake ~3 min before sleeping, so syncing now
 *   lands the trip instead of waiting for the next drive.
 */
class CarTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address
        val app = context.applicationContext
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val last = if (prefs.contains(LAST_TRIGGER)) prefs.getLong(LAST_TRIGGER, 0) else null
                val now = System.currentTimeMillis()
                if (decideCarTrigger(action, address, AppGraph.settings.current().carDeviceAddress, last, now)) {
                    // Wall clock, not elapsedRealtime: the window must survive a process death, and
                    // elapsedRealtime resets on reboot.
                    prefs.edit { putLong(LAST_TRIGGER, now) }
                    Scheduler.syncNow(app)
                }
            } catch (e: Exception) {
                Log.w(TAG, "car trigger failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "CarTrigger"
        const val PREFS = "car_trigger"
        const val LAST_TRIGGER = "last_trigger_ms"
    }
}

object BondedDevices {
    /**
     * Devices currently bonded to this phone (classic, LE and dual), as `(name, address)` pairs
     * sorted by name, for the car picker in settings. Store the chosen address in
     * `AppSettings.carDeviceAddress`. A device without a name uses its address as the name.
     *
     * Returns an empty list when Bluetooth is unavailable or off, or BLUETOOTH_CONNECT is not
     * granted; the caller should request the permission first.
     */
    fun list(context: Context): List<Pair<String, String>> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { (it.name?.takeIf(String::isNotBlank) ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
