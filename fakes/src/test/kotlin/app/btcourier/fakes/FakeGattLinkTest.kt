package app.btcourier.fakes

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class FakeGattLinkTest {
    @Test
    fun `scripted peer echoes writes until the link drops`() = runTest {
        val link = FakeGattLink { bytes, _ -> notify(bytes.reversedArray()) }

        link.write(byteArrayOf(1, 2, 3), withResponse = false)
        link.drop()

        val received = link.notifications.toList()
        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(3, 2, 1), received.single())
        assertThrows<IOException> { link.write(byteArrayOf(0), withResponse = false) }
    }

    @Test
    fun `writes larger than the MTU payload are rejected`() = runTest {
        val link = FakeGattLink(mtu = 23)
        assertThrows<IllegalArgumentException> { link.write(ByteArray(21), withResponse = true) }
    }
}
