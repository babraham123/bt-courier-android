package app.btcourier.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.btcourier.core.sink.SinkRetryState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [DataStoreSyncStatus] over a real file, including the move of the single-device sync record
 * into the per-device map, and [DataStoreRetryStates] beside it in the same file.
 */
class DataStoreSyncStatusTest {
    @TempDir
    lateinit var dir: File

    private val files = DataStoreFiles()
    private val key = stringPreferencesKey("status")
    private val dongle = "AA:BB:CC:DD:EE:01"

    // What the single-device versions wrote: the sync record at the top level, beside the rest.
    private val legacyJson =
        """{"lastSyncAtMs":1000,"lastSync":"INTERRUPTED","lastSyncLinesStored":120,"lastSyncPages":3,""" +
            """"lastSyncStop":"LINK_LOST","cursor":"1700000000:4096","pendingBatches":2,"bytesStored":5000,""" +
            """"lastUploadBySink":{"http":{"atMs":2000,"result":"DELIVERED"}},"lastError":"link lost: timeout","lastErrorAtMs":1000}"""

    private val file get() = File(dir, "status.preferences_pb")

    private fun open(legacyDevice: String? = null): DataStore<Preferences> =
        files.open(file, listOf(LegacySyncStatusMigration { legacyDevice }))

    @AfterEach
    fun tearDown() = files.closeAllBlocking()

    /** Writes what an earlier version stored, then closes that store. */
    private fun writeRaw(json: String) =
        runBlocking {
            files.open(file).edit { it[key] = json }
            files.closeAll()
        }

    @Test
    fun `upload retry schedules survive a restart beside the status, and none saved clears them`() =
        runBlocking {
            val schedules =
                mapOf(
                    "http" to SinkRetryState(streak = 2, dueAtMs = 1_790_021_600_000, reason = "HTTP 429"),
                    "sftp" to SinkRetryState(streak = 1, dueAtMs = 1_790_000_030_000, reason = "connection refused"),
                )
            val status = SyncStatus(pendingBatches = 3)
            open().let {
                DataStoreSyncStatus(it).update { status }
                DataStoreRetryStates(it).save(schedules)
            }
            files.closeAll()

            val reopened = open()
            assertEquals(schedules, DataStoreRetryStates(reopened).load())
            assertEquals(status, DataStoreSyncStatus(reopened).status.first(), "the status is untouched")

            DataStoreRetryStates(reopened).save(emptyMap())
            files.closeAll()
            assertEquals(emptyMap<String, SinkRetryState>(), DataStoreRetryStates(open()).load())
        }

    @Test
    fun `unreadable retry schedules read as none`() =
        runBlocking {
            files.open(file).edit { it[stringPreferencesKey("retries")] = """{"http":{"streak":"two"}}""" }
            files.closeAll()

            assertEquals(emptyMap<String, SinkRetryState>(), DataStoreRetryStates(open()).load())
        }

    @Test
    fun `per-device entries, the upload record and the drive session round trip through the file`() =
        runBlocking {
            val expected =
                SyncStatus(
                    lastSyncByDevice =
                        mapOf(
                            dongle to DeviceSync(1, SyncResultKind.CAUGHT_UP, 10, 2, SyncStopKind.CAUGHT_UP, "1:2"),
                            "AA:BB:CC:DD:EE:02" to DeviceSync(3, SyncResultKind.UNREACHABLE),
                            "AA:BB:CC:DD:EE:03" to
                                DeviceSync(4, SyncResultKind.NOT_DUE, cursor = "2026-10-14", dueAtMs = 86_400_000),
                        ),
                    pendingBatches = 4,
                    lastUploadBySink = mapOf("http" to SinkUpload(5, UploadResultKind.DELIVERED)),
                    driveSession = DriveSessionStatus(dongle, 6, 3, 40, 7, DriveSessionEnd.CAR_DISCONNECTED),
                )
            DataStoreSyncStatus(open()).update { expected }
            files.closeAll()

            assertEquals(expected, DataStoreSyncStatus(open()).status.first())
        }

    @Test
    fun `a device record is read back from the names earlier versions stored it under`() =
        runBlocking {
            // Every result and stop a sync records, under its persisted name; renaming one loses records.
            val results = listOf("CAUGHT_UP", "INTERRUPTED", "DEVICE_ERROR", "UNREACHABLE", "NOT_PAIRED", "NOT_DUE")
            val stops = listOf("CAUGHT_UP", "NO_PROGRESS", "LINK_LOST", "TRUNCATED", "BUSY", "DEVICE_ERROR")
            val records =
                results.flatMap { r -> stops.map { s -> r to s } }.mapIndexed { i, (r, s) ->
                    "AA:BB:CC:DD:EE:%02X".format(i) to
                        """{"atMs":$i,"result":"$r","linesStored":10,"pages":2,"stop":"$s","cursor":"1:$i"}"""
                }
            writeRaw(records.joinToString(",", """{"lastSyncByDevice":{""", "}}") { (a, json) -> "\"$a\":$json" })

            val status = DataStoreSyncStatus(open()).status.first()

            val expected =
                records
                    .mapIndexed { i, (a, _) ->
                        val (r, s) = results[i / stops.size] to stops[i % stops.size]
                        a to DeviceSync(i.toLong(), SyncResultKind.valueOf(r), 10, 2, SyncStopKind.valueOf(s), "1:$i")
                    }.toMap()
            assertEquals(expected, status.lastSyncByDevice)
            assertEquals(
                results.toSet(),
                SyncResultKind.entries.map { it.name }.toSet(),
                "a new result needs a record here",
            )
            assertEquals(stops.toSet(), SyncStopKind.entries.map { it.name }.toSet(), "a new stop needs a record here")
        }

    @Test
    fun `the single-device record becomes the entry of the device those versions synced`() =
        runBlocking {
            writeRaw(legacyJson)
            val store = open(legacyDevice = dongle)

            val status = DataStoreSyncStatus(store).status.first()

            assertEquals(
                SyncStatus(
                    lastSyncByDevice =
                        mapOf(
                            dongle to
                                DeviceSync(
                                    1000,
                                    SyncResultKind.INTERRUPTED,
                                    120,
                                    3,
                                    SyncStopKind.LINK_LOST,
                                    "1700000000:4096",
                                ),
                        ),
                    pendingBatches = 2,
                    bytesStored = 5000,
                    lastUploadBySink = mapOf("http" to SinkUpload(2000, UploadResultKind.DELIVERED)),
                    lastError = "link lost: timeout",
                    lastErrorAtMs = 1000,
                ),
                status,
            )
            val topLevel = Json.parseToJsonElement(store.data.first()[key]!!).jsonObject.keys
            assertFalse(
                topLevel.any {
                    it in setOf("lastSyncAtMs", "lastSync", "cursor")
                },
                "the legacy fields are rewritten, not kept beside the map",
            )
        }

    @Test
    fun `with nothing paired the single-device record is dropped and the rest kept`() =
        runBlocking {
            writeRaw(legacyJson)

            val status = DataStoreSyncStatus(open(legacyDevice = null)).status.first()

            assertEquals(emptyMap<String, DeviceSync>(), status.lastSyncByDevice)
            assertEquals(2, status.pendingBatches)
            assertEquals(mapOf("http" to SinkUpload(2000, UploadResultKind.DELIVERED)), status.lastUploadBySink)
        }

    @Test
    fun `a record that never synced moves nothing, and new-format status is left alone`() {
        val neverSynced = migrateLegacyStatus("""{"cursor":null,"pendingBatches":1}""", dongle)!!
        assertEquals(SyncStatus(pendingBatches = 1), Json.decodeFromString(SyncStatus.serializer(), neverSynced))

        assertNull(migrateLegacyStatus("""{"lastSyncByDevice":{},"pendingBatches":1}""", dongle))
        assertNull(migrateLegacyStatus("not json", dongle))
    }
}
