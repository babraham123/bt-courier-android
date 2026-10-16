package app.btcourier.ui

import app.btcourier.ble.PairedDevice
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceKind
import app.btcourier.data.DeviceSync
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatus
import app.btcourier.data.SyncStopKind
import app.btcourier.data.TransportConfig
import app.btcourier.data.UploadResultKind
import app.btcourier.ui.status.DeviceRow
import app.btcourier.ui.status.LastError
import app.btcourier.ui.status.LastSync
import app.btcourier.ui.status.LastUpload
import app.btcourier.ui.status.SinkRow
import app.btcourier.ui.status.StatusViewModel
import app.btcourier.work.RecordingWorkControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class StatusViewModelTest {
    private val device = PairedDevice("AA:BB:CC:DD:EE:01", "WiC_000000000000", 7)
    private val ring = PairedDevice("AA:BB:CC:DD:EE:02", "R02_0000", 8, DeviceKind.COLMI_RING)
    private val status = InMemorySyncStatus()
    private val sink =
        SinkConfig("http", "Home server", transport = TransportConfig.HttpPut("https://sink.invalid/up/"))
    private val settings = FakeSettings(AppSettings(sinks = listOf(sink), uploadOverCellular = true))
    private val devices = FakePairedDevices(device)
    private val work = RecordingWorkControl()

    private fun TestScope.subscribed(): StatusViewModel {
        val vm = StatusViewModel(status.status, settings.settings, devices, work, NoDiagnostics)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `every status field reaches the screen state`() =
        runTest {
            status.update {
                SyncStatus(
                    lastSyncByDevice =
                        mapOf(
                            device.address to
                                DeviceSync(
                                    1_000,
                                    SyncResultKind.INTERRUPTED,
                                    120,
                                    3,
                                    SyncStopKind.LINK_LOST,
                                    "1700000000:4096",
                                ),
                        ),
                    pendingBatches = 2,
                    bytesStored = 5_000,
                    droppedLines = 4,
                    droppedBytes = 400,
                    lastUploadAtMs = 2_000,
                    lastUpload = UploadResultKind.RETRYING,
                    lastUploadBySink = mapOf("http" to SinkUpload(2_000, UploadResultKind.RETRYING)),
                    lastError = "link lost: timeout",
                    lastErrorAtMs = 1_000,
                    driveSession = DriveSessionStatus(device.address, 500, rounds = 4, rows = 90),
                )
            }
            settings.update { it.copy(cacheCapBytes = 50_000_000) }

            val s = subscribed().state.value

            assertFalse(s.loading)
            assertEquals(
                listOf(
                    DeviceRow(
                        device,
                        LastSync(1_000, SyncResultKind.INTERRUPTED, 3, 120, SyncStopKind.LINK_LOST),
                        "1700000000:4096",
                    ),
                ),
                s.devices,
            )
            assertEquals(2, s.pendingBatches)
            assertEquals(5_000, s.bytesStored)
            assertEquals(50_000_000, s.cacheCapBytes)
            assertEquals(4, s.droppedLines)
            assertEquals(400, s.droppedBytes)
            assertTrue(s.sinkConfigured)
            assertEquals(LastUpload(2_000, UploadResultKind.RETRYING), s.lastUpload)
            assertEquals(listOf(SinkRow("Home server", SinkUpload(2_000, UploadResultKind.RETRYING))), s.sinks)
            assertEquals(LastError("link lost: timeout", 1_000), s.lastError)
            assertEquals(DriveSessionStatus(device.address, 500, rounds = 4, rows = 90), s.driveSession)
        }

    @Test
    fun `a sync that never reached the device shows no page counts or stop reason`() =
        runTest {
            status.update {
                it.withSync(
                    device.address,
                    DeviceSync(1_000, SyncResultKind.UNREACHABLE, pages = 9, stop = SyncStopKind.BUSY, cursor = "1:2"),
                )
            }

            val row =
                subscribed()
                    .state.value.devices
                    .single()
            val sync = row.lastSync!!

            assertEquals(SyncResultKind.UNREACHABLE, sync.result)
            assertNull(sync.pages)
            assertNull(sync.lines)
            assertNull(sync.stoppedBecause)
            assertEquals("1:2", row.cursor, "the cursor of the last sync that reached the device stays")
        }

    @Test
    fun `each paired device is listed with its kind and only its own sync, in pairing order`() =
        runTest {
            devices.devices = listOf(device, ring)
            status.update {
                it
                    .withSync(
                        ring.address,
                        DeviceSync(5, SyncResultKind.CAUGHT_UP, 9, 1, SyncStopKind.CAUGHT_UP, "2026-10-14"),
                    ).withSync("AA:BB:CC:DD:EE:09", DeviceSync(6, SyncResultKind.CAUGHT_UP))
            }

            val rows = subscribed().state.value.devices

            assertEquals(listOf(device, ring), rows.map { it.device })
            assertEquals(listOf(DeviceKind.WICAN, DeviceKind.COLMI_RING), rows.map { it.device.kind })
            assertNull(rows[0].lastSync, "the dongle never synced")
            assertNull(rows[0].cursor)
            assertEquals(LastSync(5, SyncResultKind.CAUGHT_UP, 1, 9, SyncStopKind.CAUGHT_UP), rows[1].lastSync)
            assertEquals("2026-10-14", rows[1].cursor)
        }

    @Test
    fun `the screen follows status updates`() =
        runTest {
            val vm = subscribed()
            assertNull(
                vm.state.value.devices
                    .single()
                    .lastSync,
            )

            status.update {
                it
                    .withSync(
                        device.address,
                        DeviceSync(5, SyncResultKind.CAUGHT_UP),
                    ).copy(pendingBatches = 1)
            }

            assertEquals(
                SyncResultKind.CAUGHT_UP,
                vm.state.value.devices
                    .single()
                    .lastSync
                    ?.result,
            )
            assertEquals(1, vm.state.value.pendingBatches)
        }

    @Test
    fun `without an enabled sink there is nothing to upload to`() =
        runTest {
            settings.update { it.copy(sinks = listOf(sink.copy(enabled = false))) }
            assertFalse(subscribed().state.value.sinkConfigured)

            settings.update { it.copy(sinks = emptyList()) }
            assertFalse(subscribed().state.value.sinkConfigured)
        }

    @Test
    fun `sink outcomes show the sink's name, sorted by it, and a deleted sink by its id`() =
        runTest {
            settings.update {
                it.copy(
                    sinks =
                        listOf(
                            sink,
                            SinkConfig("b2", "Archive", transport = TransportConfig.LocalFolder("content://x")),
                        ),
                )
            }
            val upload = SinkUpload(5, UploadResultKind.DELIVERED)
            status.update { it.copy(lastUploadBySink = mapOf("http" to upload, "b2" to upload, "gone" to upload)) }

            assertEquals(
                listOf("Archive", "Home server", "gone"),
                subscribed()
                    .state.value.sinks
                    .map { it.name },
            )
        }

    @Test
    fun `forget drops that device and only it disappears`() =
        runTest {
            devices.devices = listOf(device, ring)
            val vm = subscribed()

            vm.forget(ring)

            assertEquals(listOf(ring), devices.forgotten)
            assertEquals(
                listOf(device),
                vm.state.value.devices
                    .map { it.device },
            )
        }

    @Test
    fun `refreshing picks up a device paired since`() =
        runTest {
            devices.devices = emptyList()
            val vm = subscribed()
            assertEquals(emptyList<DeviceRow>(), vm.state.value.devices)

            devices.devices = listOf(device)
            vm.refreshDevices()

            assertEquals(
                listOf(device),
                vm.state.value.devices
                    .map { it.device },
            )
        }

    @Test
    fun `buttons go through the scheduler as manual syncs, upload with the cellular setting and replacing queued work`() =
        runTest {
            devices.devices = listOf(device, ring)
            val vm = subscribed()

            vm.syncNow(ring)
            vm.uploadNow()

            assertEquals(listOf(ring.address), work.manualSyncs)
            assertEquals(listOf(true to true), work.uploads)

            vm.syncAll()

            assertEquals(listOf(ring.address, device.address, ring.address), work.manualSyncs)
            assertEquals(emptyList<String>(), work.syncs, "a button is never an automatic sync")
        }
}
