package app.btcourier.work

import app.btcourier.core.transport.GattLink
import java.io.IOException

/**
 * Opens a connected, encrypted [GattLink] to the paired device: the seam between the sync
 * pipeline and the Android BLE adapter, provided through [app.btcourier.AppGraph].
 */
fun interface LinkFactory {
    /**
     * Connects to [address] (upper-case MAC), negotiates the MTU and subscribes to the stream
     * characteristic. Throws [IOException] if the device cannot be reached.
     */
    suspend fun open(address: String): GattLink

    /** Stands in until a BLE adapter is installed; every sync then reports the device unreachable. */
    object Unavailable : LinkFactory {
        override suspend fun open(address: String): GattLink = throw IOException("no BLE link adapter installed")
    }
}

/** The address of the device to sync, or null if none is paired. */
fun interface PairedAddress {
    suspend fun current(): String?
}
