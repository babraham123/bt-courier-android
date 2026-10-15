package app.btcourier.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.btcourier.core.store.StoreCounters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * What the status screen shows: the outcome of each device's latest sync and of the latest upload
 * plus the store's counters as of the latest job. Messages carry no device address or credentials.
 *
 * @property lastSyncByDevice latest sync per device, keyed by upper-case address; a device that
 *   never synced has no entry.
 */
@Serializable
data class SyncStatus(
    val lastSyncByDevice: Map<String, DeviceSync> = emptyMap(),
    val pendingBatches: Int = 0,
    /** Every stored line, sealed or not, uncompressed; what the cache cap applies to. */
    val bytesStored: Long = 0,
    val droppedLines: Long = 0,
    val droppedBytes: Long = 0,
    val lastUploadAtMs: Long? = null,
    val lastUpload: UploadResultKind? = null,
    /** Outcome of the last upload round per sink id; only the sinks configured at that time. */
    val lastUploadBySink: Map<String, SinkUpload> = emptyMap(),
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
) {
    fun withCounters(c: StoreCounters) = copy(
        pendingBatches = c.pendingBatches,
        bytesStored = c.bytesStored,
        droppedLines = c.droppedLines,
        droppedBytes = c.droppedBytes,
    )

    fun withError(message: String?, atMs: Long) =
        if (message == null) this else copy(lastError = message, lastErrorAtMs = atMs)

    fun syncOf(address: String): DeviceSync? = lastSyncByDevice[address.uppercase()]

    fun withSync(address: String, sync: DeviceSync) = copy(lastSyncByDevice = lastSyncByDevice + (address.uppercase() to sync))
}

/**
 * One device's latest sync.
 *
 * @property stop why the sync stopped; null when it never reached the device.
 * @property cursor the device's store cursor after the sync; kept from the previous sync when
 *   this one did not reach the device.
 */
@Serializable
data class DeviceSync(
    val atMs: Long,
    val result: SyncResultKind,
    val linesStored: Long = 0,
    val pages: Int = 0,
    val stop: SyncStopKind? = null,
    val cursor: String? = null,
)

enum class SyncResultKind {
    /** The device said `more:false`. */
    CAUGHT_UP,

    /** Stopped early (link lost, truncated, busy); retried with back-off, resumes at the cursor. */
    INTERRUPTED,

    /** The device refused or answered something unusable; not retried. */
    DEVICE_ERROR,

    /** The link could not be opened (out of range, asleep); the next trigger tries again. */
    UNREACHABLE,

    NOT_PAIRED,
}

/** Mirrors core's `StopReason` without its causes, so it can be persisted. */
enum class SyncStopKind { CAUGHT_UP, NO_PROGRESS, LINK_LOST, TRUNCATED, BUSY, DEVICE_ERROR }

@Serializable
data class SinkUpload(val atMs: Long, val result: UploadResultKind)

enum class UploadResultKind {
    /** Every pending batch reached every sink. */
    DELIVERED,

    /** A sink answered a transient failure; retried with back-off. */
    RETRYING,

    /** Transient failures used up the attempt cap; batches stay pending until the next upload. */
    GAVE_UP,

    /** A sink refused permanently (e.g. 403); batches stay pending until the config changes. */
    FAILED,

    NO_SINK,
}

interface SyncStatusRepository {
    val status: Flow<SyncStatus>

    suspend fun update(transform: (SyncStatus) -> SyncStatus)
}

class InMemorySyncStatus(initial: SyncStatus = SyncStatus()) : SyncStatusRepository {
    private val state = MutableStateFlow(initial)
    override val status: StateFlow<SyncStatus> = state

    override suspend fun update(transform: (SyncStatus) -> SyncStatus) = state.update(transform)
}

/**
 * The status store, with [LegacySyncStatusMigration] run before the first read. Exactly one
 * instance may exist per file, so `AppGraph` holds it.
 *
 * @param legacyDevice address of the device the single-device versions synced, for the migration.
 */
fun statusDataStore(context: Context, legacyDevice: suspend () -> String?): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(migrations = listOf(LegacySyncStatusMigration(legacyDevice))) {
        context.applicationContext.preferencesDataStoreFile("status")
    }

private val STATUS_KEY = stringPreferencesKey("status")
private val statusJson = Json { ignoreUnknownKeys = true }

/**
 * Persists [SyncStatus] as one JSON value so each update is a single atomic write and the screen
 * sees the last outcome after a process restart (workers often run while no UI process exists).
 */
class DataStoreSyncStatus(private val store: DataStore<Preferences>) : SyncStatusRepository {
    override val status: Flow<SyncStatus> = store.data.map { decodeStatus(it[STATUS_KEY]) }

    override suspend fun update(transform: (SyncStatus) -> SyncStatus) {
        store.edit { it[STATUS_KEY] = statusJson.encodeToString(SyncStatus.serializer(), transform(decodeStatus(it[STATUS_KEY]))) }
    }
}

// A status written by a future version with a renamed enum is not worth crashing a worker over.
private fun decodeStatus(value: String?): SyncStatus =
    value?.let { decodeOrNull(it, SyncStatus.serializer()) } ?: SyncStatus()

private fun <T> decodeOrNull(value: String, serializer: KSerializer<T>): T? =
    try {
        statusJson.decodeFromString(serializer, value)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * The single sync record of the single-device versions, as they wrote it at the top level of the
 * status JSON.
 */
@Serializable
private data class LegacySync(
    val lastSyncAtMs: Long? = null,
    val lastSync: SyncResultKind? = null,
    val lastSyncLinesStored: Long = 0,
    val lastSyncPages: Int = 0,
    val lastSyncStop: SyncStopKind? = null,
    val cursor: String? = null,
)

private val LEGACY_SYNC_FIELDS = setOf("lastSyncAtMs", "lastSync", "lastSyncLinesStored", "lastSyncPages", "lastSyncStop", "cursor")

/**
 * Status JSON with the single-device sync record moved into [SyncStatus.lastSyncByDevice] under
 * [address], the device those versions synced; without an [address] (nothing paired any more), or
 * when the record holds no sync, the record is dropped. Everything else is kept. Null when [json]
 * has no legacy record or is unreadable, so there is nothing to rewrite.
 */
internal fun migrateLegacyStatus(json: String, address: String?): String? {
    val fields = (decodeOrNull(json, JsonObject.serializer()) ?: return null).keys
    if (fields.none { it in LEGACY_SYNC_FIELDS }) return null
    val status = decodeOrNull(json, SyncStatus.serializer()) ?: return null
    val legacy = decodeOrNull(json, LegacySync.serializer())
    val result = legacy?.lastSync
    val migrated = if (address == null || result == null || status.syncOf(address) != null) {
        status
    } else {
        status.withSync(
            address,
            DeviceSync(legacy.lastSyncAtMs ?: 0, result, legacy.lastSyncLinesStored, legacy.lastSyncPages, legacy.lastSyncStop, legacy.cursor),
        )
    }
    return statusJson.encodeToString(SyncStatus.serializer(), migrated)
}

/** Runs [migrateLegacyStatus] on the stored status before DataStore serves its first read. */
class LegacySyncStatusMigration(private val legacyDevice: suspend () -> String?) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[STATUS_KEY]?.let { migrateLegacyStatus(it, null) } != null

    override suspend fun migrate(currentData: Preferences): Preferences {
        val json = currentData[STATUS_KEY] ?: return currentData
        val migrated = migrateLegacyStatus(json, legacyDevice()) ?: return currentData
        return currentData.toMutablePreferences().apply { this[STATUS_KEY] = migrated }.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
