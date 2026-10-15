package app.btcourier.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.UUID

/**
 * Device Information Service revisions: 16-bit UUIDs over the Bluetooth base UUID
 * `0000xxxx-0000-1000-8000-00805F9B34FB` (Core spec Vol 3 Part B 2.5.1), Firmware Revision `0x2A26`
 * and Hardware Revision `0x2A27` in service `0x180A`; each is optional, so a missing one is null.
 */
class DeviceInformationTest {
    private class ReadableLink(
        private val values: Map<UUID, ByteArray>,
        private val dropped: Boolean = false,
    ) : GattLink {
        override val mtu = 23
        override val notifications: Flow<ByteArray> = emptyFlow()

        override suspend fun write(
            bytes: ByteArray,
            withResponse: Boolean,
        ) = Unit

        override suspend fun read(
            service: UUID,
            characteristic: UUID,
        ): ByteArray {
            if (dropped) throw IOException("link lost")
            if (service != DIS) throw CharacteristicUnavailableException(service, characteristic)
            return values[characteristic] ?: throw CharacteristicUnavailableException(service, characteristic)
        }

        override fun close() = Unit
    }

    @Test
    fun `16-bit numbers expand over the Bluetooth base UUID`() {
        assertEquals(DIS, DeviceInformation.SERVICE)
        assertEquals(FIRMWARE, DeviceInformation.FIRMWARE_REVISION)
        assertEquals(HARDWARE, DeviceInformation.HARDWARE_REVISION)
    }

    @Test
    fun `both revisions are read as UTF-8 strings, trailing NULs and whitespace dropped`() =
        runTest {
            val link =
                ReadableLink(
                    mapOf(
                        FIRMWARE to "3.00.10\u0000\u0000".encodeToByteArray(),
                        HARDWARE to " V1.0 ".encodeToByteArray(),
                    ),
                )
            assertEquals(DeviceRevisions(firmware = "3.00.10", hardware = "V1.0"), link.readDeviceRevisions())
        }

    @Test
    fun `a missing or blank revision is null`() =
        runTest {
            assertEquals(
                DeviceRevisions(firmware = "3.00.10", hardware = null),
                ReadableLink(mapOf(FIRMWARE to "3.00.10".encodeToByteArray())).readDeviceRevisions(),
            )
            assertEquals(
                DeviceRevisions(firmware = null, hardware = null),
                ReadableLink(mapOf(FIRMWARE to ByteArray(2))).readDeviceRevisions(),
            )
        }

    @Test
    fun `a link that reads nothing has no revisions, and a dropped link fails`() =
        runTest {
            val readsNothing =
                object : GattLink {
                    override val mtu = 23
                    override val notifications: Flow<ByteArray> = emptyFlow()

                    override suspend fun write(
                        bytes: ByteArray,
                        withResponse: Boolean,
                    ) = Unit

                    override fun close() = Unit
                }
            val missing = assertThrows<CharacteristicUnavailableException> { readsNothing.read(DIS, FIRMWARE) }
            assertEquals(DIS to FIRMWARE, missing.service to missing.characteristic)
            assertEquals(DeviceRevisions(null, null), readsNothing.readDeviceRevisions())

            val dropped = ReadableLink(emptyMap(), dropped = true)
            val e = assertThrows<IOException> { dropped.readDeviceRevisions() }
            assertEquals(IOException::class.java, e::class.java)
        }

    private companion object {
        val DIS: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val FIRMWARE: UUID = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")
        val HARDWARE: UUID = UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB")
    }
}
