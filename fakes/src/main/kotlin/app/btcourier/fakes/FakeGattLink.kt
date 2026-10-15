package app.btcourier.fakes

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
 */
class FakeGattLink(
    override val mtu: Int = 517,
    /** Called after each accepted write; lets a peripheral script respond via [notify]. */
    private val onWrite: suspend FakeGattLink.(bytes: ByteArray, withResponse: Boolean) -> Unit = { _, _ -> },
) : GattLink {
    // Unbounded so a test can queue notifications before anyone collects, like a real stack's
    // buffer; only one collector is expected, matching a single transport per link.
    private val pending = Channel<ByteArray>(Channel.UNLIMITED)

    private val _writes = mutableListOf<ByteArray>()
    private val _withResponse = mutableListOf<Boolean>()

    /** Copies of every PDU written, in order. */
    val writes: List<ByteArray> get() = synchronized(_writes) { _writes.toList() }

    /** The `withResponse` flag of each entry in [writes]. */
    val writesWithResponse: List<Boolean> get() = synchronized(_writes) { _withResponse.toList() }

    @Volatile
    var closed: Boolean = false
        private set

    override val notifications: Flow<ByteArray> = pending.receiveAsFlow()

    override suspend fun write(bytes: ByteArray, withResponse: Boolean) {
        if (closed) throw IOException("link closed")
        require(bytes.size <= mtu - 3) { "PDU of ${bytes.size} bytes exceeds MTU payload ${mtu - 3}" }
        synchronized(_writes) {
            _writes += bytes.copyOf()
            _withResponse += withResponse
        }
        onWrite(bytes, withResponse)
        // A real stack suspends on every write; without this a writer loop would never let the
        // notification collector run, hiding interleavings the transport must handle.
        yield()
    }

    /** Delivers one notification PDU to the collector. */
    fun notify(bytes: ByteArray) {
        pending.trySend(bytes.copyOf())
    }

    /** Simulates the peer dropping the link: notifications complete and further writes fail. */
    fun drop() {
        closed = true
        pending.close()
    }

    override fun close() = drop()
}
