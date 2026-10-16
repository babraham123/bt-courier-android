package app.btcourier.ble

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.data.SettingsRepository
import app.btcourier.data.kindOf
import app.btcourier.data.with
import app.btcourier.data.without

/**
 * A device the user associated through the companion-device dialog.
 *
 * [address] is the address Android saw during the association scan. The WiCAN advertises a
 * rotating resolvable private address; once bonded, Android keeps resolving that first-seen
 * address to the device's identity, so it stays valid for later connects.
 *
 * @property vehicleProfile an ELM327's stored vehicle profile ([DeviceConfig.vehicleProfile]),
 *   which its source needs; null for the generic one and for other kinds.
 */
data class PairedDevice(
    val address: String,
    val displayName: String?,
    val associationId: Int?,
    val kind: DeviceKind = DeviceKind.WICAN,
    val vehicleProfile: String? = null,
)

/** One `CompanionDeviceManager` association, which carries no app metadata. */
data class Association(
    val address: String,
    val displayName: String?,
    val id: Int?,
)

/** The app's associations; the seam that keeps [CompanionDeviceManager] out of JVM tests. */
interface Associations {
    /** In creation order. */
    fun list(): List<Association>

    /** Drops the association (and with it background-start privileges tied to it). */
    fun remove(association: Association)
}

/** Every paired device, in pairing order. */
fun interface PairedDevices {
    suspend fun all(): List<PairedDevice>
}

/** Where paired devices are remembered. */
interface PairedDeviceRepository : PairedDevices {
    /** Records what the association cannot hold; call right after the association is made. */
    suspend fun remember(device: DeviceConfig)

    suspend fun forget(device: PairedDevice)
}

/**
 * Paired devices are the [Associations] themselves rather than a copy of their addresses in app
 * storage: the system already persists them, and an association the user removes in system
 * settings then disappears here too instead of leaving a stale copy. [SettingsRepository] only
 * adds each one's [DeviceConfig].
 */
class CompanionPairedDeviceRepository(
    private val associations: Associations,
    private val settings: SettingsRepository,
) : PairedDeviceRepository {
    override suspend fun all(): List<PairedDevice> = pairedDevices(associations.list(), settings.current().devices)

    override suspend fun remember(device: DeviceConfig) {
        val entry = device.copy(address = device.address.uppercase())
        settings.update { it.copy(devices = it.devices.with(entry)) }
    }

    override suspend fun forget(device: PairedDevice) {
        associations.remove(Association(device.address, device.displayName, device.associationId))
        settings.update { it.copy(devices = it.devices.without(device.address)) }
    }
}

/**
 * Reads associations back from [CompanionDeviceManager]. Addresses are upper-cased, the form
 * Android reports for scanned devices and the key of everything stored per device.
 */
class CompanionAssociations(
    context: Context,
) : Associations {
    private val cdm: CompanionDeviceManager? = context.getSystemService(CompanionDeviceManager::class.java)

    override fun list(): List<Association> {
        val cdm = cdm ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.mapNotNull { info ->
                val mac = info.deviceMacAddress ?: return@mapNotNull null
                Association(mac.toString().uppercase(), info.displayName?.toString(), info.id)
            }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.map { Association(it.uppercase(), null, null) }
        }
    }

    @SuppressLint("MissingPermission") // disassociate needs no runtime permission.
    override fun remove(association: Association) {
        val cdm = cdm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && association.id != null) {
            cdm.disassociate(association.id)
        } else {
            @Suppress("DEPRECATION")
            cdm.disassociate(association.address)
        }
    }
}

/**
 * One [PairedDevice] per address, with its kind, name and vehicle profile from [configs] (an
 * address without one is a WiCAN, see [DeviceConfig]). Re-pairing a device (after a factory
 * reset) creates a second association for the same address; the newest wins: association ids
 * increase monotonically, and pre-33 lists are in creation order.
 */
internal fun pairedDevices(
    associations: List<Association>,
    configs: List<DeviceConfig>,
): List<PairedDevice> {
    val ordered = if (associations.all { it.id != null }) associations.sortedBy { it.id } else associations
    return ordered.associateBy { it.address.uppercase() }.values.map { a ->
        val config = configs.firstOrNull { it.address.equals(a.address, ignoreCase = true) }
        PairedDevice(
            a.address.uppercase(),
            a.displayName ?: config?.name,
            a.id,
            configs.kindOf(a.address),
            config?.vehicleProfile,
        )
    }
}

/**
 * The device the single-device versions synced: they picked the newest association, by the same
 * ordering as [pairedDevices].
 */
internal fun newestAssociation(associations: List<Association>): Association? =
    if (associations.all { it.id != null }) associations.maxByOrNull { it.id!! } else associations.lastOrNull()
