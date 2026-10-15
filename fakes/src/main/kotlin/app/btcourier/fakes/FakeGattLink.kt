package app.btcourier.fakes

import app.btcourier.core.transport.ChannelUnavailableException
import app.btcourier.core.transport.GattChannel
import app.btcourier.core.transport.GattLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield
import java.io.IOException

/**
 * In-memory [GattLink] for JVM tests: records every write and lets the test push notifications.
 *
 * Scripted peripherals (frame replay, link loss, 429) build on this by reacting in [onWrite].
 * A link built with [onSecondaryWrite] also has the secondary channel, with its own writes and
 * notifications; without it, the secondary channel is unavailable, as on a device lacking it.
 */
class FakeGattLink(
    override val mtu: Int = 517,
    /** Called after each accepted secondary-channel write; null means the link has no secondary channel. */
    private val onSecondaryWrite: (suspend FakeGattLink.(bytes: ByteArray, withResponse: Boolean) -> Unit)? = null,
    /** Called after each accepted write; lets a peripheral script respond via [notify]. */
    private val onWrite: suspend FakeGattLink.(bytes: ByteArray, withResponse: Boolean) -> Unit = { _, _ -> },
) : GattLink {
    // Unbounded so a test can queue notifications before anyone collects, like a real stack's
    // buffer; only one collector per channel is expected, matching a single transport per link.
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)
    private val secondaryPending = Channel<ByteArray>(Channel.UNLIMITED)

    private val _writes = mutableListOf<ByteArray>()
    private val _writesWithResponse = mutableListOf<Boolean>()
    private val _secondaryWrites = mutableListOf<ByteArray>()

    /** Copies of every primary-channel PDU written, in order. */
    val writes: List<ByteArray> get() = synchronized(_writes) { _writes.toList() }

    /** The `withResponse` flag of each entry in [writes]. */
    val writesWithResponse: List<Boolean> get() = synchronized(_writes) { _writesWithResponse.toList() }

    /** Copies of every secondary-channel PDU written, in order. */
    val secondaryWrites: List<ByteArray> get() = synchronized(_writes) { _secondaryWrites.toList() }

    @Volatile
    var closed: Boolean = false
        private set

    override val notifications: Flow<ByteArray> = pending.receiveAsFlow()
    private val secondaryNotifications: Flow<ByteArray> = secondaryPending.receiveAsFlow()

    override fun notifications(channel: GattChannel): Flow<ByteArray> =
        if (channel == GattChannel.SECONDARY &&
            onSecondaryWrite != null
        ) {
            secondaryNotifications
        } else {
            super.notifications(channel)
        }

    override suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
    ) = write(bytes, withResponse, GattChannel.PRIMARY)

    override suspend fun write(
        bytes: ByteArray,
        withResponse: Boolean,
        channel: GattChannel,
    ) {
        if (closed) throw IOException("link closed")
        val secondary = channel == GattChannel.SECONDARY
        if (secondary && onSecondaryWrite == null) throw ChannelUnavailableException(channel)
        require(bytes.size <= mtu - 3) { "PDU of ${bytes.size} bytes exceeds MTU payload ${mtu - 3}" }
        synchronized(_writes) {
            if (secondary) {
                _secondaryWrites += bytes.copyOf()
            } else {
                _writes += bytes.copyOf()
                _writesWithResponse += withResponse
            }
        }
        if (secondary) checkNotNull(onSecondaryWrite)(bytes, withResponse) else onWrite(bytes, withResponse)
        // A real stack suspends on every write; without this a writer loop would never let the
        // notification collector run, hiding interleavings the transport must handle.
        yield()
    }

    /** Delivers one notification PDU on [channel] to its collector. */
    fun notify(
        bytes: ByteArray,
        channel: GattChannel = GattChannel.PRIMARY,
    ) {
        check(channel == GattChannel.PRIMARY || onSecondaryWrite != null) { "the link has no secondary channel" }
        (if (channel == GattChannel.PRIMARY) pending else secondaryPending).trySend(bytes.copyOf())
    }

    /** Simulates the peer dropping the link: notifications complete and further writes fail. */
    fun drop() {
        closed = true
        pending.close()
        secondaryPending.close()
    }

    override fun close() = drop()
}
