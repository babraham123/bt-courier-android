package app.btcourier.ble

import app.btcourier.core.transport.GattLink
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.fakes.FakeGattLink
import app.btcourier.ui.FakeSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.UUID

/**
 * The production link factory's profile choice: a fixed-profile kind opens its profile; an
 * ELM327 opens the layout stored with it, or, without one, the layout discovery picks, which is
 * then stored for the next link (ticket 0002).
 */
class KableLinkFactoryTest {
    private fun short(value: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))

    private val address = "AA:BB:CC:DD:EE:03"
    private val ffe0 = GattLayout(short(0xFFE0), short(0xFFE1), short(0xFFE1))
    private val fff0Services =
        listOf(
            DiscoveredService(
                short(0xFFF0),
                listOf(
                    DiscoveredCharacteristic(short(0xFFF1), notify = true, write = false),
                    DiscoveredCharacteristic(short(0xFFF2), notify = false, write = true),
                ),
            ),
        )

    /** Discovers [services] on every connect and records what was opened. */
    private class RecordingConnector(
        private val services: List<DiscoveredService>,
    ) : GattConnector {
        val opened = mutableListOf<Triple<String, Int, GattProfile>>()
        val link = FakeGattLink()

        override suspend fun connect(
            address: String,
            mtu: Int,
            resolveProfile: (List<DiscoveredService>) -> GattProfile,
        ): GattLink {
            opened += Triple(address, mtu, resolveProfile(services))
            return link
        }
    }

    @Test
    fun `fixed-profile kinds open their own profile, whatever the device exposes`() =
        runTest {
            val connector = RecordingConnector(fff0Services)
            val factory = KableLinkFactory({ FakeSettings() }, connector)

            factory.open(address, DeviceKind.WICAN)
            factory.open(address, DeviceKind.COLMI_RING)

            assertEquals(
                listOf(
                    Triple(address, WicanGatt.PREFERRED_MTU, WicanGatt.PROFILE),
                    Triple(address, ColmiRingGatt.PROFILE.mtu, ColmiRingGatt.PROFILE),
                ),
                connector.opened,
            )
        }

    @Test
    fun `an ELM327 with a stored layout opens it without searching`() =
        runTest {
            val settings =
                FakeSettings(
                    AppSettings(devices = listOf(DeviceConfig(address.lowercase(), DeviceKind.ELM327, gatt = ffe0))),
                )
            val connector = RecordingConnector(fff0Services)

            val link = KableLinkFactory({ settings }, connector).open(address, DeviceKind.ELM327)

            assertSame(connector.link, link)
            assertEquals(listOf(Triple(address, 517, Elm327Gatt.DISCOVERY.profile(ffe0))), connector.opened)
        }

    @Test
    fun `an ELM327 without one opens the discovered layout and stores it for the next link`() =
        runTest {
            val other = DeviceConfig("AA:BB:CC:DD:EE:01", DeviceKind.WICAN)
            val settings =
                FakeSettings(
                    AppSettings(
                        devices = listOf(other, DeviceConfig(address, DeviceKind.ELM327, "vLinker MC+", "Kia: EV6")),
                    ),
                )
            val connector = RecordingConnector(fff0Services)
            val logged = mutableListOf<String>()
            val factory = KableLinkFactory({ settings }, connector, log = { logged += it })
            val fff0 = GattLayout(short(0xFFF0), short(0xFFF1), short(0xFFF2))

            factory.open(address, DeviceKind.ELM327)

            assertEquals(Elm327Gatt.DISCOVERY.profile(fff0), connector.opened.single().third)
            assertEquals(
                listOf(
                    "ELM327 GATT layout: service ${fff0.service}, notify ${fff0.notify}, write ${fff0.write}, bonded false, mtu 517",
                ),
                logged,
            )
            assertEquals(
                listOf(other, DeviceConfig(address, DeviceKind.ELM327, "vLinker MC+", "Kia: EV6", gatt = fff0)),
                settings.current().devices,
            )
        }

    @Test
    fun `a device with no usable service fails to open and stores nothing`() =
        runTest {
            val stored = listOf(DeviceConfig(address, DeviceKind.ELM327))
            val settings = FakeSettings(AppSettings(devices = stored))
            val factory = KableLinkFactory({ settings }, RecordingConnector(emptyList()))

            assertThrows<IOException> { factory.open(address, DeviceKind.ELM327) }
            assertEquals(stored, settings.current().devices)
        }

    @Test
    fun `a device without an entry is searched but not added to the settings`() =
        runTest {
            val settings = FakeSettings()

            KableLinkFactory({ settings }, RecordingConnector(fff0Services)).open(address, DeviceKind.ELM327)

            assertEquals(emptyList<DeviceConfig>(), settings.current().devices)
        }
}
