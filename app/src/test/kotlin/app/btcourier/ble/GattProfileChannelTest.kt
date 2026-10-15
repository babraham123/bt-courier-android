package app.btcourier.ble

import app.btcourier.core.transport.GattChannel
import com.juul.kable.WriteType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The secondary channel of a profile. The ring's big-data UUIDs are from PulseLoopiOS
 * `ColmiProtocol.swift` and Gadgetbridge YawellRing: service DE5BF728, requests written to
 * DE5BF72A, answers notified on DE5BF729.
 */
class GattProfileChannelTest {
    private fun bigData(short: String) = UUID.fromString("$short-D711-4E47-AF26-65E3012A5DC7")

    @Test
    fun `the ring declares the big-data service as its secondary channel`() {
        assertEquals(
            GattProfile.ChannelSpec(bigData("DE5BF728"), notify = bigData("DE5BF729"), write = bigData("DE5BF72A")),
            ColmiRingGatt.PROFILE.channel(GattChannel.SECONDARY),
        )
    }

    @Test
    fun `a secondary write goes to the big-data write characteristic in its own service under the ring's write type rule`() {
        val ring = ColmiRingGatt.PROFILE

        fun route(
            withResponse: Boolean,
            supported: Boolean,
        ) = ring.routeWrite(withResponse, supported, GattChannel.SECONDARY)

        val withResponse = GattProfile.WriteRoute(bigData("DE5BF72A"), WriteType.WithResponse)
        val withoutResponse = GattProfile.WriteRoute(bigData("DE5BF72A"), WriteType.WithoutResponse)
        assertEquals(GattProfile.ChannelWrite(bigData("DE5BF728"), withResponse), route(true, supported = true))
        // As on RX: a characteristic without the write property gets the write without response.
        assertEquals(GattProfile.ChannelWrite(bigData("DE5BF728"), withoutResponse), route(true, supported = false))
        assertEquals(GattProfile.ChannelWrite(bigData("DE5BF728"), withoutResponse), route(false, supported = true))
    }

    @Test
    fun `the primary channel routes as the profile's own service and write characteristic`() {
        val ring = ColmiRingGatt.PROFILE
        assertEquals(GattProfile.ChannelSpec(ring.service, ring.notify, ring.write), ring.channel(GattChannel.PRIMARY))
        for (supported in listOf(true, false)) {
            assertEquals(
                GattProfile.ChannelWrite(ring.service, ring.routeWrite(true, supported)),
                ring.routeWrite(true, supported, GattChannel.PRIMARY),
            )
        }
        assertEquals(
            GattProfile.ChannelWrite(WicanGatt.SERVICE, WicanGatt.PROFILE.routeWrite(true, false)),
            WicanGatt.PROFILE.routeWrite(true, false, GattChannel.PRIMARY),
        )
    }

    @Test
    fun `the WiCAN has no secondary channel`() {
        assertNull(WicanGatt.PROFILE.channel(GattChannel.SECONDARY))
        assertNull(WicanGatt.PROFILE.routeWrite(true, true, GattChannel.SECONDARY))
    }
}
