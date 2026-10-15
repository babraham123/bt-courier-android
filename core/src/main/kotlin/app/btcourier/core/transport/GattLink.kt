package app.btcourier.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.UUID

/**
 * A connected BLE link to a device's notify and write characteristics.
 *
 * Kept free of Android types so the device codecs and transports can be tested on the JVM
 * against fakes; the app adapts `BluetoothGatt` to this.
 *
 * Every link has the [primary][GattChannel.PRIMARY] channel (the WiCAN's `ble_http` stream, the
 * ring's UART), which [notifications] and the two-argument [write] address. A device may also
 * offer a [secondary][GattChannel.SECONDARY] channel in a service of its own (the ring's
 * big-data service), addressed by the overloads taking a [GattChannel]. Both share the link's
 * [mtu]. The overloads default to "primary only", so a link without a second channel need not
 * implement them.
 *
 * Apart from the channels, [read] fetches a readable characteristic once by its UUIDs (the Device
 * Information Service's strings); it defaults to "reads nothing".
 */
interface GattLink {
    /** Negotiated ATT MTU; usable payload per write/notification is `mtu - 3`. */
    val mtu: Int

    /**
     * Notifications from the primary channel's notify characteristic, one element per PDU, in
     * arrival order. Completes when the link drops.
     */
    val notifications: Flow<ByteArray>

    /**
     * Writes one PDU of at most `mtu - 3` bytes to the primary channel. Suspends until the stack
     * accepts it (and, with [withResponse], until the peer acknowledges it). Throws
     * [java.io.IOException] on link loss.
     */
    suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
    )

    /**
     * Notifications from [channel], as [notifications] for the primary one. Collecting a channel
     * the link does not have fails with [ChannelUnavailableException].
     */
    fun notifications(channel: GattChannel): Flow<ByteArray> =
        if (channel == GattChannel.PRIMARY) notifications else flow { throw ChannelUnavailableException(channel) }

    /**
     * Writes one PDU to [channel], as [write] does to the primary one.
     *
     * @throws ChannelUnavailableException if the link does not have [channel].
     */
    suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
        channel: GattChannel,
    ) {
        if (channel == GattChannel.PRIMARY) write(bytes, withResponse) else throw ChannelUnavailableException(channel)
    }

    /**
     * Reads [characteristic] of [service] once (an ATT read, not a notification).
     *
     * @throws CharacteristicUnavailableException when the device does not expose it, or not readably.
     * @throws IOException on link loss.
     */
    suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray = throw CharacteristicUnavailableException(service, characteristic)

    /** Disconnects. Idempotent. */
    fun close()
}

/** Which of a link's notify/write characteristic pairs an operation uses. */
enum class GattChannel { PRIMARY, SECONDARY }

/**
 * The link has no [channel]: the device profile does not declare it, or the device does not
 * expose it (a model without the service). Unlike other [IOException]s it says nothing about
 * the link itself, which stays usable on its other channel.
 */
class ChannelUnavailableException(
    val channel: GattChannel,
    cause: Throwable? = null,
) : IOException("the link has no $channel channel", cause)

/**
 * The device does not expose [characteristic] in [service], or not readably to this link. Like
 * [ChannelUnavailableException] it says nothing about the link itself, which stays usable.
 */
class CharacteristicUnavailableException(
    val service: UUID,
    val characteristic: UUID,
    cause: Throwable? = null,
) : IOException("the device has no characteristic $characteristic in service $service", cause)
