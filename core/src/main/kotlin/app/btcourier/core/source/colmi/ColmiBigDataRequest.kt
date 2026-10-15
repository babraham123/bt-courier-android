package app.btcourier.core.source.colmi

import app.btcourier.core.transport.ChannelUnavailableException
import app.btcourier.core.transport.GattChannel
import app.btcourier.core.transport.GattLink
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.timeout
import java.io.IOException
import kotlin.time.Duration

/** No whole big-data answer arrived in time; the link itself may be fine. */
class ColmiBigDataTimeoutException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Writes the [history request][ColmiBigData.historyRequest] for [type] on the link's secondary
 * channel and reassembles its answer from that channel's notifications, waiting up to [timeout]
 * for each of them. Answers of other types are skipped. Written without response, like the
 * UART commands; unverified on hardware.
 *
 * @return the answer's payload.
 * @throws ColmiBigDataTimeoutException when the answer is not whole in time.
 * @throws ChannelUnavailableException when the link has no big-data channel.
 * @throws IOException when the link drops.
 */
@OptIn(FlowPreview::class)
suspend fun GattLink.colmiBigDataRequest(
    type: Int,
    timeout: Duration,
): ByteArray {
    write(ColmiBigData.historyRequest(type), false, GattChannel.SECONDARY)
    val assembler = ColmiBigDataAssembler()
    val frame =
        try {
            notifications(GattChannel.SECONDARY)
                .timeout(timeout)
                .mapNotNull { assembler.feed(it) }
                .filter { it.type == type }
                .firstOrNull()
        } catch (e: TimeoutCancellationException) {
            throw ColmiBigDataTimeoutException("no complete big-data answer 0x%02x within %s".format(type, timeout), e)
        }
    return frame?.payload ?: throw IOException("link dropped during big-data request 0x%02x".format(type))
}

/**
 * The big-data answers of one connection. Every answer holds all the days the ring keeps, so
 * each type is requested at most once per instance and served from here afterwards.
 *
 * A type whose answer times out is taken as unsupported by this model and reads as an empty
 * payload for the rest of the connection; so does every type when the link has no big-data
 * channel. Any other link failure propagates, so the caller's day fails and is retried.
 */
internal class ColmiBigDataLogs(
    private val link: GattLink,
    private val timeout: Duration,
) {
    private val answers = mutableMapOf<Int, ByteArray>()

    suspend fun payload(type: Int): ByteArray =
        answers.getOrPut(type) {
            try {
                link.colmiBigDataRequest(type, timeout)
            } catch (e: ColmiBigDataTimeoutException) {
                EMPTY
            } catch (e: ChannelUnavailableException) {
                ColmiBigData.HISTORY_TYPES.forEach { answers[it] = EMPTY }
                EMPTY
            }
        }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
