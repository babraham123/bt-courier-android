package app.btcourier.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * into the per-device map.
 */
class DataStoreSyncStatusTest {
    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val key = stringPreferencesKey("status")
    private val dongle = "AA:BB:CC:DD:EE:01"

    // What the single-device versions wrote: the sync record at the top level, beside the rest.
    private val legacyJson = """{"lastSyncAtMs":1000,"lastSync":"INTERRUPTED","lastSyncLinesStored":120,"lastSyncPages":3,""" +
        """"lastSyncStop":"LINK_LOST","cursor":"1700000000:4096","pendingBatches":2,"bytesStored":5000,""" +
        """"lastUploadBySink":{"http":{"atMs":2000,"result":"DELIVERED"}},"lastError":"link lost: timeout","lastErrorAtMs":1000}"""

    private fun open(legacyDevice: String? = null): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(migrations = listOf(LegacySyncStatusMigration { legacyDevice }), scope = scope) {
            File(dir, "status.preferences_pb")
        }
    }

    private fun closeAll() = scopes.forEach { it.cancel() }.also { scopes.clear() }

    @AfterEach
    fun tearDown() = closeAll()

    private fun writeRaw(json: String) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "status.preferences_pb") }.edit { it[key] = json }
        scope.cancel()
    }

    @Test
    fun `per-device entries and the upload record round trip through the file`() = runBlocking {
        val expected = SyncStatus(
            lastSyncByDevice = mapOf(
                dongle to DeviceSync(1, SyncResultKind.CAUGHT_UP, 10, 2, SyncStopKind.CAUGHT_UP, "1:2"),
                "AA:BB:CC:DD:EE:02" to DeviceSync(3, SyncResultKind.UNREACHABLE),
            ),
            pendingBatches = 4,
            lastUploadBySink = mapOf("http" to SinkUpload(5, UploadResultKind.DELIVERED)),
        )
        DataStoreSyncStatus(open()).update { expected }
        closeAll()

        assertEquals(expected, DataStoreSyncStatus(open()).status.first())
    }

    @Test
    fun `the single-device record becomes the entry of the device those versions synced`() = runBlocking {
        writeRaw(legacyJson)
        val store = open(legacyDevice = dongle)

        val status = DataStoreSyncStatus(store).status.first()

        assertEquals(
            SyncStatus(
                lastSyncByDevice = mapOf(dongle to DeviceSync(1000, SyncResultKind.INTERRUPTED, 120, 3, SyncStopKind.LINK_LOST, "1700000000:4096")),
                pendingBatches = 2,
                bytesStored = 5000,
                lastUploadBySink = mapOf("http" to SinkUpload(2000, UploadResultKind.DELIVERED)),
                lastError = "link lost: timeout",
                lastErrorAtMs = 1000,
            ),
            status,
        )
        val topLevel = Json.parseToJsonElement(store.data.first()[key]!!).jsonObject.keys
        assertFalse(topLevel.any { it in setOf("lastSyncAtMs", "lastSync", "cursor") }, "the legacy fields are rewritten, not kept beside the map")
    }

    @Test
    fun `with nothing paired the single-device record is dropped and the rest kept`() = runBlocking {
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
