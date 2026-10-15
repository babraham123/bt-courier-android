package app.btcourier.ble

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build

/**
 * A device the user associated through the companion-device dialog.
 *
 * [address] is the address Android saw during the association scan. The WiCAN advertises a
 * rotating resolvable private address; once bonded, Android keeps resolving that first-seen
 * address to the device's identity, so it stays valid for later connects.
 */
data class PairedDevice(val address: String, val displayName: String?, val associationId: Int?)

/** Where the paired device is remembered. Settings storage is not the source of truth for this. */
interface PairedDeviceRepository {
    /** The device to sync with, or null when nothing is associated. */
    fun current(): PairedDevice?

    /** Drops the association (and with it background-start privileges tied to it). */
    fun forget(device: PairedDevice)
}

/**
 * Reads the association back from [CompanionDeviceManager] itself instead of copying the
 * address into app storage: the system already persists it, and an association the user
 * removes in system settings then disappears here too instead of leaving a stale copy.
 */
class CompanionPairedDeviceRepository(context: Context) : PairedDeviceRepository {
    private val cdm: CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)

    override fun current(): PairedDevice? {
        val cdm = cdm ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            selectPaired(
                cdm.myAssociations.mapNotNull { info ->
                    val mac = info.deviceMacAddress ?: return@mapNotNull null
                    PairedDevice(mac.toString().uppercase(), info.displayName?.toString(), info.id)
                },
            )
        } else {
            @Suppress("DEPRECATION")
            selectPaired(cdm.associations.map { PairedDevice(it.uppercase(), null, null) })
        }
    }

    @SuppressLint("MissingPermission") // disassociate needs no runtime permission.
    override fun forget(device: PairedDevice) {
        val cdm = cdm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && device.associationId != null) {
            cdm.disassociate(device.associationId)
        } else {
            @Suppress("DEPRECATION")
            cdm.disassociate(device.address)
        }
    }
}

/**
 * The app syncs one device. If the user associated several (re-pairing after a factory reset
 * creates a new association), the newest wins: association ids increase monotonically, and
 * pre-33 lists are in creation order.
 */
internal fun selectPaired(associations: List<PairedDevice>): PairedDevice? =
    if (associations.all { it.associationId != null }) {
        associations.maxByOrNull { it.associationId!! }
    } else {
        associations.lastOrNull()
    }
