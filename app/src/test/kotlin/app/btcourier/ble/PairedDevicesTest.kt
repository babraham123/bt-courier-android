package app.btcourier.ble

import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceConfig
import app.btcourier.data.DeviceKind
import app.btcourier.ui.FakeSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [CompanionPairedDeviceRepository]: the associations are the paired devices, the settings'
 * device list only adds kind and name, and an association without an entry is a WiCAN.
 */
class PairedDevicesTest {
    private val dongle = Association("AA:BB:CC:DD:EE:01", "WiC_000000000000", 3)
    private val ring = Association("AA:BB:CC:DD:EE:02", null, 5)

    private class FakeAssociations(
        vararg initial: Association,
    ) : Associations {
        val associations = initial.toMutableList()

        override fun list(): List<Association> = associations.toList()

        override fun remove(association: Association) {
            associations.removeAll { it.address == association.address && it.id == association.id }
        }
    }

    @Test
    fun `an ELM327 comes back with its stored vehicle profile, for its source`() =
        runTest {
            val adapter = Association("AA:BB:CC:DD:EE:03", null, 7)
            val settings =
                FakeSettings(
                    AppSettings(
                        devices = listOf(DeviceConfig(adapter.address, DeviceKind.ELM327, "vLinker MC+", "Kia: EV6")),
                    ),
                )
            val repo = CompanionPairedDeviceRepository(FakeAssociations(adapter), settings)

            assertEquals(
                listOf(PairedDevice(adapter.address, "vLinker MC+", 7, DeviceKind.ELM327, "Kia: EV6")),
                repo.all(),
            )
        }

    @Test
    fun `associations come back with their kind, a missing entry meaning WiCAN`() =
        runTest {
            val settings =
                FakeSettings(
                    AppSettings(devices = listOf(DeviceConfig("aa:bb:cc:dd:ee:02", DeviceKind.COLMI_RING, "R02_0000"))),
                )
            val repo = CompanionPairedDeviceRepository(FakeAssociations(dongle, ring), settings)

            assertEquals(
                listOf(
                    PairedDevice(dongle.address, "WiC_000000000000", 3, DeviceKind.WICAN),
                    PairedDevice(ring.address, "R02_0000", 5, DeviceKind.COLMI_RING),
                ),
                repo.all(),
            )
        }

    @Test
    fun `remembering stores the entry upper-cased and replaces an older one for the address`() =
        runTest {
            val settings = FakeSettings(AppSettings(devices = listOf(DeviceConfig(ring.address, DeviceKind.WICAN))))
            val repo = CompanionPairedDeviceRepository(FakeAssociations(ring), settings)

            repo.remember(DeviceConfig("aa:bb:cc:dd:ee:02", DeviceKind.COLMI_RING, "R02_0000"))

            assertEquals(
                listOf(DeviceConfig(ring.address, DeviceKind.COLMI_RING, "R02_0000")),
                settings.current().devices,
            )
            assertEquals(DeviceKind.COLMI_RING, repo.all().single().kind)
        }

    @Test
    fun `forget removes the association and its entry, and nothing else`() =
        runTest {
            val associations = FakeAssociations(dongle, ring)
            val settings =
                FakeSettings(AppSettings(devices = listOf(DeviceConfig(ring.address, DeviceKind.COLMI_RING))))
            val repo = CompanionPairedDeviceRepository(associations, settings)

            repo.forget(repo.all().last())

            assertEquals(listOf(dongle), associations.associations)
            assertEquals(emptyList<DeviceConfig>(), settings.current().devices)
            assertEquals(listOf(dongle.address), repo.all().map { it.address })
        }

    @Test
    fun `a re-paired address is listed once, as its newest association`() {
        val older = Association("AA:BB:CC:DD:EE:01", "WiC_a", 3)
        val newer = Association("aa:bb:cc:dd:ee:01", "WiC_b", 7)

        assertEquals(
            listOf(PairedDevice("AA:BB:CC:DD:EE:01", "WiC_b", 7)),
            pairedDevices(listOf(newer, older), emptyList()),
        )
        // Pre-API-33 associations have no id; the list is in creation order.
        val legacy = listOf(Association("AA:BB:CC:DD:EE:01", null, null), Association("AA:BB:CC:DD:EE:01", "x", null))
        assertEquals("x", pairedDevices(legacy, emptyList()).single().displayName)
    }

    @Test
    fun `the device the single-device versions synced is the newest association`() {
        assertEquals(ring, newestAssociation(listOf(ring, dongle)))
        assertNull(newestAssociation(emptyList()))
        val legacy = listOf(Association("AA:BB:CC:DD:EE:01", null, null), Association("AA:BB:CC:DD:EE:02", null, null))
        assertEquals(legacy.last(), newestAssociation(legacy))
    }
}
