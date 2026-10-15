package app.btcourier.core.source.colmi

import app.btcourier.core.transport.GattLink
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.transform
import java.io.IOException
import kotlin.time.Duration

/**
 * Writes the ring command [packet] and assembles its answer from the notifications on this link,
 * waiting up to [timeout] for each packet of it. The write asks for a response; the app's ring
 * link sends it without one when RX does not declare write with response (PulseLoopiOS's rule).
 *
 * Packets of other commands (the ring may send those at any time), notifications that are not
 * whole 16-byte packets and packets failing the checksum are skipped, so a corrupted packet of the
 * answer surfaces as the timeout. A notification that holds several packets is consumed whole:
 * packets after the one that completes the answer are dropped. Not acknowledged; unverified on
 * hardware, from `colmi_r02_client` and Gadgetbridge.
 *
 * @throws IOException on timeout or when the link drops before the answer is whole.
 */
@OptIn(FlowPreview::class)
suspend fun <T : Any> GattLink.colmiRequest(
    packet: ByteArray,
    timeout: Duration,
    assembler: ColmiAssembler<T>,
): T {
    val command = packet[0]
    write(packet, true)
    val result =
        try {
            notifications
                .timeout(timeout)
                .transform { pdu -> ColmiPacketCodec.packets(pdu).forEach { emit(it) } }
                .filter { ColmiPacketCodec.isValid(it) && it[0] == command }
                .mapNotNull { assembler.feed(it) }
                .firstOrNull()
        } catch (e: TimeoutCancellationException) {
            throw IOException("no complete answer to command 0x%02x within %s".format(command, timeout), e)
        }
    return result ?: throw IOException("link dropped during command 0x%02x".format(command))
}
