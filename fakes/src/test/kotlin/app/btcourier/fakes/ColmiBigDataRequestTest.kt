package app.btcourier.fakes

import app.btcourier.core.source.colmi.ColmiTimeoutException
import app.btcourier.core.source.colmi.colmiBigDataRequest
import app.btcourier.core.transport.ChannelUnavailableException
import app.btcourier.core.transport.GattChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** [colmiBigDataRequest] on a [FakeGattLink]: requests on the secondary channel, answers reassembled from it. */
class ColmiBigDataRequestTest {
    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `writes the request on the secondary channel and returns the payload of the answer of its type`() =
        runTest {
            val payload = ByteArray(30) { it.toByte() }
            val link =
                FakeGattLink(mtu = 23, onSecondaryWrite = { request, _ ->
                    // An answer of another type first; then the SpO2 answer in two notifications.
                    notify(bytes(0xBC, 0x27, 1, 0, 0, 0, 0), GattChannel.SECONDARY)
                    val frame = bytes(0xBC, request[1].toInt(), payload.size, 0, 0, 0) + payload
                    notify(frame.copyOfRange(0, 20), GattChannel.SECONDARY)
                    notify(frame.copyOfRange(20, frame.size), GattChannel.SECONDARY)
                })

            assertArrayEquals(payload, link.colmiBigDataRequest(0x2A, 5.seconds))
            val request = bytes(0xBC, 0x2A, 1, 0, 0xFF, 0, 0xFF)
            assertEquals(listOf(request.toList()), link.secondaryWrites.map { it.toList() })
            assertEquals(emptyList<ByteArray>(), link.writes)
        }

    @Test
    fun `no whole answer in time is a timeout, not a link failure`() =
        runTest {
            val link =
                FakeGattLink(mtu = 23, onSecondaryWrite = { _, _ ->
                    // Announces 30 bytes, sends 14.
                    notify(bytes(0xBC, 0x25, 30, 0, 0, 0) + ByteArray(14), GattChannel.SECONDARY)
                })
            assertThrows<ColmiTimeoutException> { link.colmiBigDataRequest(0x25, 5.seconds) }
            assertFalse(link.closed)
        }

    @Test
    fun `a link without the channel and a link that drops fail differently`() =
        runTest {
            assertThrows<ChannelUnavailableException> { FakeGattLink(mtu = 23).colmiBigDataRequest(0x27, 5.seconds) }

            val dropping = FakeGattLink(mtu = 23, onSecondaryWrite = { _, _ -> drop() })
            val failure = assertThrows<IOException> { dropping.colmiBigDataRequest(0x27, 5.seconds) }
            assertFalse(failure is ColmiTimeoutException || failure is ChannelUnavailableException)
        }
}
