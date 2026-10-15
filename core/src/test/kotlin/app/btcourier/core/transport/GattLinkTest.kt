package app.btcourier.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A link implementing only the primary channel gets the channel overloads for free. */
class GattLinkTest {
    private class PrimaryOnly : GattLink {
        val written = mutableListOf<Pair<List<Byte>, Boolean>>()
        override val mtu = 23
        override val notifications: Flow<ByteArray> = flowOf(byteArrayOf(7))

        override suspend fun write(
            bytes: ByteArray,
            withResponse: Boolean,
        ) {
            written += bytes.toList() to withResponse
        }

        override fun close() = Unit
    }

    @Test
    fun `the primary channel overloads reach the primary members`() =
        runTest {
            val link = PrimaryOnly()
            link.write(byteArrayOf(1), withResponse = true, channel = GattChannel.PRIMARY)
            assertEquals(listOf(listOf<Byte>(1) to true), link.written)
            assertSame(link.notifications, link.notifications(GattChannel.PRIMARY))
        }

    @Test
    fun `the secondary channel is unavailable unless the link implements it`() =
        runTest {
            val link = PrimaryOnly()
            val write =
                assertThrows<ChannelUnavailableException> {
                    link.write(byteArrayOf(1), withResponse = false, channel = GattChannel.SECONDARY)
                }
            assertEquals(GattChannel.SECONDARY, write.channel)
            assertThrows<ChannelUnavailableException> { link.notifications(GattChannel.SECONDARY).toList() }
            assertEquals(emptyList<Any>(), link.written)
        }
}
