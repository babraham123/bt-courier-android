package app.btcourier.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * A connected, encrypted BLE link to the device's `ble_http` stream characteristic.
 *
 * Kept free of Android types so the `ble_http` codec and transport can be tested on the JVM
 * against fakes; the app adapts `BluetoothGatt` to this.
 */
interface GattLink {
    /** Negotiated ATT MTU; usable payload per write/notification is `mtu - 3`. */
    val mtu: Int

    /**
     * Notifications from the stream characteristic, one element per PDU, in arrival order.
     * Completes when the link drops.
     */
    val notifications: Flow<ByteArray>

    /**
     * Writes one PDU of at most `mtu - 3` bytes. Suspends until the stack accepts it (and, with
     * [withResponse], until the peer acknowledges it). Throws [java.io.IOException] on link loss.
     */
    suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
    )

    /** Disconnects. Idempotent. */
    fun close()
}
