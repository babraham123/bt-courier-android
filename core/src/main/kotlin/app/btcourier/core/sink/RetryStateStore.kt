package app.btcourier.core.sink

import kotlinx.serialization.Serializable

/**
 * Where [Delivery] keeps every sink's retry schedule, so a sink that asked for a long wait is not
 * hit again right after the process dies. Adapters: [MemoryRetryStateStore] (tests, and
 * [Delivery]'s default) and the app's, in its status DataStore.
 *
 * [Delivery] is the only user: it loads once, keeps the current map itself and serialises its
 * saves, so an adapter only has to hand back on [load] the map last passed to [save].
 */
interface RetryStateStore {
    /** Every sink's schedule as last saved, keyed by sink id; empty if none was. */
    suspend fun load(): Map<String, SinkRetryState>

    /** Durably replaces every schedule with [states]. */
    suspend fun save(states: Map<String, SinkRetryState>)
}

/**
 * One sink's schedule after [streak] consecutive [UploadResult.Retry]s: not to be contacted before
 * [dueAtMs] (the delivery's clock), last told [reason]. Never credentials: [reason] is the
 * transport's classification, the same text the status screen shows.
 *
 * Persisted as JSON by the app, so the field names are its storage format.
 */
@Serializable
data class SinkRetryState(
    val streak: Int,
    val dueAtMs: Long,
    val reason: String,
)

/** Volatile adapter; outlives the [Delivery]s created on it, which is how tests restart. */
class MemoryRetryStateStore : RetryStateStore {
    @Volatile
    private var saved: Map<String, SinkRetryState> = emptyMap()

    override suspend fun load(): Map<String, SinkRetryState> = saved

    override suspend fun save(states: Map<String, SinkRetryState>) {
        saved = states
    }
}
