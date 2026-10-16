package app.btcourier.ui

import android.text.format.DateUtils
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.btcourier.R
import app.btcourier.ble.PairedDevice
import app.btcourier.data.AppSettings
import app.btcourier.data.DeviceSync
import app.btcourier.data.DriveSessionEnd
import app.btcourier.data.DriveSessionStatus
import app.btcourier.data.InMemorySyncStatus
import app.btcourier.data.SinkConfig
import app.btcourier.data.SinkUpload
import app.btcourier.data.SyncResultKind
import app.btcourier.data.SyncStatus
import app.btcourier.data.SyncStopKind
import app.btcourier.data.TransportConfig
import app.btcourier.data.UploadResultKind
import app.btcourier.ui.status.StatusScreen
import app.btcourier.ui.status.StatusViewModel
import app.btcourier.ui.status.formatStatusTime
import app.btcourier.work.RecordingWorkControl
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusScreenTest : ScreenTest() {
    private val device = PairedDevice("AA:BB:CC:DD:EE:01", "WiC_000000000000", 7)
    private val sink = SinkConfig("http", "Home server", transport = TransportConfig.HttpPut(SINK_URL))
    private val settings = FakeSettings(AppSettings(sinks = listOf(sink)))
    private val devices = FakePairedDevices(device)
    private val work = RecordingWorkControl()
    private var pairOpened = 0

    private fun show(initial: SyncStatus = SyncStatus()) {
        val vm = StatusViewModel(InMemorySyncStatus(initial).status, settings.settings, devices, work, NoDiagnostics)
        setScreen { StatusScreen(vm, onPair = { pairOpened++ }, onSettings = {}) }
    }

    @Test
    fun `a paired device shows its name, kind, address and last sync`() {
        show(
            SyncStatus(
                lastSyncByDevice =
                    mapOf(
                        device.address to
                            DeviceSync(1_000, SyncResultKind.INTERRUPTED, 3, 120, SyncStopKind.LINK_LOST, CURSOR),
                    ),
            ),
        )

        compose.onNodeWithText(device.displayName!!).assertIsDisplayed()
        infoRow(R.string.status_device_kind, string(R.string.kind_wican)).assertIsDisplayed()
        infoRow(R.string.status_device_address, device.address).assertIsDisplayed()
        infoRow(R.string.status_result, string(R.string.sync_interrupted)).assertIsDisplayed()
        infoRow(R.string.status_pages_lines, string(R.string.status_pages_lines_value, 120, 3L)).assertIsDisplayed()
        infoRow(R.string.status_stopped_because, string(R.string.stop_link_lost)).performScrollTo().assertIsDisplayed()
        infoRow(R.string.status_cursor, CURSOR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a skipped sync says until when nothing is due, and keeps the cursor`() {
        val due = 1_790_000_000_000L
        show(
            SyncStatus(
                lastSyncByDevice =
                    mapOf(
                        device.address to
                            DeviceSync(1_000, SyncResultKind.NOT_DUE, cursor = CURSOR, dueAtMs = due),
                    ),
            ),
        )

        infoRow(R.string.status_result, string(R.string.sync_not_due, formatStatusTime(app, due))).assertIsDisplayed()
        assertNoText(R.string.status_pages_lines)
        infoRow(R.string.status_cursor, CURSOR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the cache and the last upload show pending batches, drops and each sink's outcome`() {
        show(
            SyncStatus(
                pendingBatches = 2,
                droppedLines = 4,
                lastUploadAtMs = 2_000,
                lastUpload = UploadResultKind.RETRYING,
                lastUploadBySink = mapOf(sink.id to SinkUpload(2_000, UploadResultKind.RETRYING)),
            ),
        )

        infoRow(R.string.status_pending_batches, "2").performScrollTo().assertIsDisplayed()
        infoRow(string(R.string.status_dropped), app.resources.getQuantityString(R.plurals.status_dropped_lines, 4, 4L))
            .performScrollTo()
            .assertIsDisplayed()
        infoRow(R.string.status_result, string(R.string.upload_retrying)).performScrollTo().assertIsDisplayed()
        infoRow(string(R.string.status_sink, sink.name), string(R.string.upload_retrying))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun rounds(n: Int) = app.resources.getQuantityString(R.plurals.drive_session_rounds, n, n)

    private fun rows(n: Long) = app.resources.getQuantityString(R.plurals.drive_session_rows, n.toInt(), n)

    @Test
    fun `a running drive session shows its rounds, rows and start time`() {
        val start = 1_790_000_000_000L
        show(SyncStatus(driveSession = DriveSessionStatus(device.address, start, rounds = 3, rows = 120)))

        val since = DateUtils.formatDateTime(app, start, DateUtils.FORMAT_SHOW_TIME)
        onText(R.string.drive_session_title).performScrollTo().assertIsDisplayed()
        onText(R.string.drive_session_running, rounds(3), rows(120), since).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an ended drive session shows when and why it ended, with its totals`() {
        val start = 1_790_000_000_000L
        val end = start + 3_600_000
        show(
            SyncStatus(
                driveSession = DriveSessionStatus(device.address, start, 30, 1, end, DriveSessionEnd.CAR_DISCONNECTED),
            ),
        )

        onText(
            R.string.drive_session_ended,
            formatStatusTime(app, end),
            string(R.string.drive_end_car_disconnected),
            rounds(30),
            rows(1),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without a drive session there is no drive session section`() {
        show()

        assertNoText(R.string.drive_session_title)
    }

    @Test
    fun `sync now syncs that device and upload now replaces the queued upload`() {
        show()

        onText(R.string.status_sync_now).tap()
        onText(R.string.status_upload_now).tap()

        compose.runOnIdle {
            assertEquals(listOf(device.address), work.manualSyncs)
            assertEquals(listOf(false to true), work.uploads)
        }
    }

    @Test
    fun `the last error renders its message`() {
        show(SyncStatus(lastError = "upload failed: HTTP 503", lastErrorAtMs = 3_000))

        onText(R.string.status_last_error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("upload failed: HTTP 503").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with nothing paired it says so and offers pairing`() {
        devices.devices = emptyList()
        show()

        onText(R.string.home_not_paired).assertIsDisplayed()
        onText(R.string.home_pair).tap()

        assertEquals(1, pairOpened)
        assertNoText(R.string.status_sync_now)
    }

    @Test
    fun `forgetting a device unpairs it and leaves the empty state`() {
        show()

        onText(R.string.home_forget).tap()

        onText(R.string.home_not_paired).assertIsDisplayed()
        assertEquals(listOf(device), devices.forgotten)
    }

    @Test
    fun `upload now is disabled with a hint until a sink is set up`() {
        settings.settings.value = AppSettings()
        show()

        onText(R.string.status_no_sink_hint).performScrollTo().assertIsDisplayed()
        onText(R.string.status_upload_now).performScrollTo().assertIsNotEnabled()
    }

    private companion object {
        const val SINK_URL = "https://sink.invalid/up/"
        const val CURSOR = "1700000000:4096"
    }
}
