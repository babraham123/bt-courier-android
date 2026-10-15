package app.btcourier.ui

import android.net.Uri
import app.btcourier.ble.PairedDevice
import app.btcourier.data.AppSettings
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatus
import app.btcourier.data.SyncStopKind
import app.btcourier.data.UploadResultKind
import app.btcourier.ui.status.DiagnosticsExport
import app.btcourier.ui.status.LastError
import app.btcourier.ui.status.LastSync
import app.btcourier.ui.status.LastUpload
import app.btcourier.ui.status.StatusViewModel
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
    private val device = PairedDevice("AA:BB:CC:DD:EE:FF", "WiC_000000000000", 7)
    private val status = InMemorySyncStatus()
    private val settings = FakeSettings(AppSettings(sinkUrl = "https://sink.invalid/up/", uploadOverCellular = true))
    private val devices = FakePairedDevices(device)
    private val work = RecordingWorkControl()

    private val diagnostics = object : DiagnosticsExport {
        override suspend fun text() = "log"

        override suspend fun save(target: Uri, text: String) = Unit
    }

    private fun TestScope.subscribed(): StatusViewModel {
        val vm = StatusViewModel(status.status, settings.settings, devices, work, diagnostics)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test
    fun `every status field reaches the screen state`() = runTest {
        status.update {
            SyncStatus(
                lastSyncAtMs = 1_000,
                lastSync = SyncResultKind.INTERRUPTED,
                lastSyncLinesStored = 120,
                lastSyncPages = 3,
                lastSyncStop = SyncStopKind.LINK_LOST,
                cursor = "1700000000:4096",
                pendingBatches = 2,
                bytesStored = 5_000,
                droppedLines = 4,
                droppedBytes = 400,
                lastUploadAtMs = 2_000,
                lastUpload = UploadResultKind.RETRYING,
                lastUploadBySink = mapOf("http" to SinkUpload(2_000, UploadResultKind.RETRYING)),
                lastError = "link lost: timeout",
                lastErrorAtMs = 1_000,
            )
        }
        settings.update { it.copy(cacheCapBytes = 50_000_000) }

        val s = subscribed().state.value

        assertFalse(s.loading)
        assertEquals(device, s.device)
        assertEquals(LastSync(1_000, SyncResultKind.INTERRUPTED, 3, 120, SyncStopKind.LINK_LOST), s.lastSync)
        assertEquals("1700000000:4096", s.cursor)
        assertEquals(2, s.pendingBatches)
        assertEquals(5_000, s.bytesStored)
        assertEquals(50_000_000, s.cacheCapBytes)
        assertEquals(4, s.droppedLines)
        assertEquals(400, s.droppedBytes)
        assertTrue(s.sinkConfigured)
        assertEquals(LastUpload(2_000, UploadResultKind.RETRYING), s.lastUpload)
        assertEquals(listOf("http" to SinkUpload(2_000, UploadResultKind.RETRYING)), s.sinks)
        assertEquals(LastError("link lost: timeout", 1_000), s.lastError)
    }

    @Test
    fun `a sync that never reached the device shows no page counts or stop reason`() = runTest {
        status.update {
            it.copy(lastSyncAtMs = 1_000, lastSync = SyncResultKind.UNREACHABLE, lastSyncPages = 9, lastSyncStop = SyncStopKind.BUSY)
        }

        val sync = subscribed().state.value.lastSync!!

        assertEquals(SyncResultKind.UNREACHABLE, sync.result)
        assertNull(sync.pages)
        assertNull(sync.lines)
        assertNull(sync.stoppedBecause)
    }

    @Test
    fun `the screen follows status updates`() = runTest {
        val vm = subscribed()
        assertNull(vm.state.value.lastSync)

        status.update { it.copy(lastSyncAtMs = 5, lastSync = SyncResultKind.CAUGHT_UP, pendingBatches = 1) }

        assertEquals(SyncResultKind.CAUGHT_UP, vm.state.value.lastSync?.result)
        assertEquals(1, vm.state.value.pendingBatches)
    }

    @Test
    fun `without an upload URL there is no sink to upload to`() = runTest {
        settings.update { it.copy(sinkUrl = " ") }

        assertFalse(subscribed().state.value.sinkConfigured)
    }

    @Test
    fun `forget drops the association and the device disappears`() = runTest {
        val vm = subscribed()

        vm.forget()

        assertEquals(listOf(device), devices.forgotten)
        assertNull(vm.state.value.device)
    }

    @Test
    fun `refreshing picks up a device paired since`() = runTest {
        devices.device = null
        val vm = subscribed()
        assertNull(vm.state.value.device)

        devices.device = device
        vm.refreshDevice()

        assertEquals(device, vm.state.value.device)
    }

    @Test
    fun `buttons go through the scheduler, upload with the cellular setting and replacing queued work`() = runTest {
        val vm = subscribed()

        vm.syncNow()
        vm.uploadNow()

        assertEquals(1, work.syncs)
        assertEquals(listOf(true to true), work.uploads)
    }
}
