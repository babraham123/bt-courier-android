package app.btcourier.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.btcourier.core.store.StoreCounters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the status screen shows: the outcome of the latest sync and upload plus the store's
 * counters as of the latest job. Messages carry no device address or credentials.
 */
@Serializable
data class SyncStatus(
    val lastSyncAtMs: Long? = null,
    val lastSync: SyncResultKind? = null,
    val lastSyncLinesStored: Long = 0,
    val lastSyncPages: Int = 0,
    /** Why the last sync that reached the device stopped; null when it never got that far. */
    val lastSyncStop: SyncStopKind? = null,
    val cursor: String? = null,
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
}

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

private val Context.statusStore: DataStore<Preferences> by preferencesDataStore(name = "status")

/**
 * Persists [SyncStatus] as one JSON value so each update is a single atomic write and the screen
 * sees the last outcome after a process restart (workers often run while no UI process exists).
 */
class DataStoreSyncStatus(context: Context) : SyncStatusRepository {
    private val store = context.applicationContext.statusStore
    private val key = stringPreferencesKey("status")
    private val json = Json { ignoreUnknownKeys = true }

    override val status: Flow<SyncStatus> = store.data.map { decode(it[key]) }

    override suspend fun update(transform: (SyncStatus) -> SyncStatus) {
        store.edit { it[key] = json.encodeToString(SyncStatus.serializer(), transform(decode(it[key]))) }
    }

    // A status written by a future version with a renamed enum is not worth crashing a worker over.
    private fun decode(value: String?): SyncStatus =
        value?.let {
            try {
                json.decodeFromString(SyncStatus.serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: SyncStatus()
}
