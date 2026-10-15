package app.btcourier.work

import app.btcourier.core.transport.GattLink
import app.btcourier.data.DeviceKind
import java.io.IOException

/**
 * Opens a connected [GattLink] to a paired device: the seam between the sync pipeline and the
 * Android BLE adapter (`KableGattLink` in production), provided through [app.btcourier.AppGraph].
 * Syncs and the pair wizard's probe both open links through it, under [DeviceLocks].
 */
fun interface LinkFactory {
    /**
     * Connects to [address] (upper-case MAC) with [kind]'s GATT profile, negotiates the MTU and
     * subscribes to its notify characteristic. Throws [IOException] if the device cannot be reached.
     */
    suspend fun open(
        address: String,
        kind: DeviceKind,
    ): GattLink

    /** Stands in until a BLE adapter is installed; every sync then reports the device unreachable. */
    object Unavailable : LinkFactory {
        override suspend fun open(
            address: String,
            kind: DeviceKind,
        ): GattLink = throw IOException("no BLE link adapter installed")
    }
}
