package app.btcourier.fakes

import app.btcourier.core.transport.ChannelUnavailableException
import app.btcourier.core.transport.GattChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class FakeGattLinkTest {
    @Test
    fun `scripted peer echoes writes until the link drops`() =
        runTest {
            val link = FakeGattLink { bytes, _ -> notify(bytes.reversedArray()) }

            link.write(byteArrayOf(1, 2, 3), withResponse = false)
            link.drop()

            val received = link.notifications.toList()
            assertEquals(1, received.size)
            assertArrayEquals(byteArrayOf(3, 2, 1), received.single())
            assertThrows<IOException> { link.write(byteArrayOf(0), withResponse = false) }
        }

    @Test
    fun `writes larger than the MTU payload are rejected`() =
        runTest {
            val link = FakeGattLink(mtu = 23)
            assertThrows<IllegalArgumentException> { link.write(ByteArray(21), withResponse = true) }
        }

    @Test
    fun `the secondary channel carries its own writes and notifications, apart from the primary`() =
        runTest {
            val link =
                FakeGattLink(
                    mtu = 23,
                    onSecondaryWrite = { bytes, _ -> notify(bytes + 9, GattChannel.SECONDARY) },
                ) { bytes, _ -> notify(bytes + 1) }

            link.write(byteArrayOf(5), withResponse = false, channel = GattChannel.SECONDARY)
            link.write(byteArrayOf(6), withResponse = true)
            assertThrows<IllegalArgumentException> {
                link.write(ByteArray(21), withResponse = false, channel = GattChannel.SECONDARY)
            }
            link.drop()

            assertEquals(listOf(listOf<Byte>(6)), link.writes.map { it.toList() })
            assertEquals(listOf(listOf<Byte>(5)), link.secondaryWrites.map { it.toList() })
            assertEquals(listOf(listOf<Byte>(6, 1)), link.notifications.toList().map { it.toList() })
            // The secondary flow completes with the link, like the primary one.
            assertEquals(
                listOf(listOf<Byte>(5, 9)),
                link.notifications(GattChannel.SECONDARY).toList().map { it.toList() },
            )
            assertThrows<IOException> {
                link.write(
                    byteArrayOf(0),
                    withResponse = false,
                    channel = GattChannel.SECONDARY,
                )
            }
        }

    @Test
    fun `a link without the secondary channel reports it unavailable and keeps the primary working`() =
        runTest {
            val link = FakeGattLink(mtu = 23) { bytes, _ -> notify(bytes) }

            assertThrows<ChannelUnavailableException> {
                link.write(byteArrayOf(1), withResponse = false, channel = GattChannel.SECONDARY)
            }
            assertThrows<ChannelUnavailableException> { link.notifications(GattChannel.SECONDARY).toList() }
            assertEquals(emptyList<ByteArray>(), link.secondaryWrites)

            link.write(byteArrayOf(2), withResponse = false)
            link.drop()
            assertEquals(listOf(listOf<Byte>(2)), link.notifications.toList().map { it.toList() })
        }
}
